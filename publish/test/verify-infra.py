#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
秒杀核心链路「基础设施层」真机验证脚本
================================================
不启动 Spring 应用，直接拿项目里的**真实产物**打真实中间件，验证三件事：

  A. Redis + lua/seckill.lua   —— 库存扣减 / 一人一单 / 并发不超卖
  B. MySQL + 真实 SQL          —— CAS 关单幂等：重复投递库存只回补一次
  C. RabbitMQ 拓扑             —— 延迟队列 TTL + 死信交换机 能真正转投

依赖：pip install redis pymysql pika
用法：
    python test/verify-infra.py             # 全跑
    python test/verify-infra.py --keep-db   # 跑完保留建好的秒杀表（默认保留，见下）

说明：
  - A 会在 nyw:seckill:* 上写测试数据，跑完会重置成 init-seckill-redis.txt 的状态（stock=3000、清空下单集合）
  - B 会执行 test/jmeter/init-seckill-test.sql 建表（幂等），并插入/删除一条测试订单，库存原值还原
  - C 用 verify. 前缀建临时队列/交换机（避免和应用的 seckill.* 抢参数），跑完自动删除
"""

import argparse
import concurrent.futures as cf
import pathlib
import sys
import time

HOST = "${MW_HOST}"
REDIS_PORT = 6379
MYSQL_PORT = 3306
MQ_PORT = 5672
PASSWORD = "${MW_PASSWORD}"
DB = "nyw"
ROOT = pathlib.Path(__file__).resolve().parent.parent

STOCK_KEY = "nyw:seckill:stock:1"
ORDER_SET_KEY = "nyw:seckill:order:set:1"
INIT_STOCK = 3000
TEST_ORDER_ID = 7653178267000000001  # 19 位，模拟修复后的分布式 ID

OK = "  \033[32m[PASS]\033[0m"
NO = "  \033[31m[FAIL]\033[0m"
failures = []


def check(cond, msg, extra=""):
    print(f"{OK if cond else NO} {msg}" + (f"  {extra}" if extra else ""))
    if not cond:
        failures.append(msg)
    return cond


def title(t):
    print(f"\n{'=' * 72}\n{t}\n{'=' * 72}")


# ---------------------------------------------------------------- A. Redis + Lua
def verify_redis_lua():
    import redis

    title("A. Redis + lua/seckill.lua：库存 / 一人一单 / 并发不超卖")
    lua_path = ROOT / "seckill-service/src/main/resources/lua/seckill.lua"
    script = lua_path.read_text(encoding="utf-8")
    print(f"  脚本：{lua_path.relative_to(ROOT)}（{len(script.splitlines())} 行）")

    pool = redis.ConnectionPool(host=HOST, port=REDIS_PORT, password=PASSWORD,
                                decode_responses=True, max_connections=250)
    r = redis.Redis(connection_pool=pool)
    print(f"  目标：redis://{HOST}:{REDIS_PORT}  PING={r.ping()}  连接池上限=250")

    sha = r.script_load(script)

    def seckill(user_id):
        """完全等价于 SeckillServiceImpl 的 stringRedisTemplate.execute(script, [stock, set], userId)"""
        return r.evalsha(sha, 2, STOCK_KEY, ORDER_SET_KEY, str(user_id))

    # --- 重置到 init-seckill-redis.txt 的状态
    r.set(STOCK_KEY, INIT_STOCK)
    r.delete(ORDER_SET_KEY)
    print(f"  初始化：stock={r.get(STOCK_KEY)}，下单集合已清空\n")

    # A1 一人一单
    first = seckill(10001)
    second = seckill(10001)
    check(first == 0 and second == 2,
          "A1 同一用户连下两次：第一次放行、第二次被一人一单拦住",
          f"返回码=[{first}, {second}]（0=成功 2=已下单）")

    # A2 并发下单，不超卖
    r.set(STOCK_KEY, INIT_STOCK)
    r.delete(ORDER_SET_KEY)
    users = list(range(20000, 20000 + INIT_STOCK + 200))
    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=200) as pool:
        results = list(pool.map(seckill, users))
    cost = time.time() - t0
    success = results.count(0)
    sold_out = results.count(1)
    dup = results.count(2)
    stock_left = int(r.get(STOCK_KEY))
    check(success == INIT_STOCK and sold_out == 200 and dup == 0 and stock_left == 0,
          f"A2 {len(users)} 个用户并发抢 {INIT_STOCK} 库存：不超卖、不超发",
          f"成功={success} 库存不足={sold_out} 重复={dup} 剩余库存={stock_left} 耗时={cost:.2f}s")

    # A3 库存为 0 后不再放行
    late = seckill(99999)
    check(late == 1, "A3 库存扣完后新用户只能拿到「库存不足」", f"返回码={late}")

    # A4 库存永不为负
    check(int(r.get(STOCK_KEY)) >= 0, "A4 库存从未变成负数", f"stock={r.get(STOCK_KEY)}")

    # --- 还原成 init-seckill-redis.txt 的状态，方便后续压测
    r.set(STOCK_KEY, INIT_STOCK)
    r.delete(ORDER_SET_KEY)
    print(f"  已还原：stock={r.get(STOCK_KEY)}，下单集合已清空（与 init-seckill-redis.txt 一致）")
    return success, cost


# --------------------------------------------------------- B. MySQL CAS 幂等
def verify_mysql_idempotent():
    import pymysql

    title("B. MySQL 真实 SQL：CAS 关单幂等 —— 重复投递库存只回补一次")
    conn = pymysql.connect(host=HOST, port=MYSQL_PORT, user="root",
                           password=PASSWORD, database=DB, autocommit=True)
    cur = conn.cursor()

    # B0 建表（执行项目自带的初始化脚本，CREATE TABLE IF NOT EXISTS 幂等）
    sql_file = ROOT / "test/jmeter/init-seckill-test.sql"
    raw = sql_file.read_text(encoding="utf-8")
    stmts = [s.strip() for s in raw.split(";") if s.strip() and not all(
        l.strip().startswith("--") or not l.strip() for l in s.strip().splitlines())]
    for s in stmts:
        cur.execute("\n".join(l for l in s.splitlines() if not l.strip().startswith("--")))
    print(f"  已执行 {sql_file.relative_to(ROOT)}（建表 + 初始化 voucher_id=1，stock=3000）")

    cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=1")
    stock_before = int(cur.fetchone()[0])
    check(stock_before == INIT_STOCK, "B0 秒杀券 1 就绪", f"stock={stock_before}")

    # 造一条「未支付」订单
    cur.execute("DELETE FROM seckill_order WHERE id=%s", (TEST_ORDER_ID,))
    cur.execute("""INSERT INTO seckill_order (id, user_id, voucher_id, item_id, status, create_time)
                   VALUES (%s, 20001, 1, 1, 1, NOW())""", (TEST_ORDER_ID,))

    close_sql = "UPDATE seckill_order SET status=3 WHERE id=%s AND status=1"
    restore_sql = "UPDATE seckill_voucher SET stock=stock+1 WHERE voucher_id=%s"

    # B1 第一次投递：CAS 生效，回补一次
    cur.execute(close_sql, (TEST_ORDER_ID,))
    rows1 = cur.rowcount
    if rows1:
        cur.execute(restore_sql, (1,))
    cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=1")
    stock_after_1 = int(cur.fetchone()[0])
    check(rows1 == 1 and stock_after_1 == stock_before + 1,
          "B1 第一次投递：CAS 关单成功 → 回补 1 次库存",
          f"CAS行数={rows1} 库存 {stock_before}→{stock_after_1}")

    # B2 重复投递同一条消息：CAS 0 行 → 不进入回补分支
    cur.execute(close_sql, (TEST_ORDER_ID,))
    rows2 = cur.rowcount
    if rows2:
        cur.execute(restore_sql, (1,))
    cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=1")
    stock_after_2 = int(cur.fetchone()[0])
    check(rows2 == 0 and stock_after_2 == stock_after_1,
          "B2 ★重复投递同一消息：CAS 0 行，库存不再变化（面试必问）",
          f"CAS行数={rows2} 库存仍为 {stock_after_2}")

    # B3 订单状态
    cur.execute("SELECT status FROM seckill_order WHERE id=%s", (TEST_ORDER_ID,))
    status = int(cur.fetchone()[0])
    check(status == 3, "B3 订单最终是「已关闭」", f"status={status}")

    # 还原现场
    cur.execute("DELETE FROM seckill_order WHERE id=%s", (TEST_ORDER_ID,))
    cur.execute("UPDATE seckill_voucher SET stock=%s WHERE voucher_id=1", (stock_before,))
    cur.execute("SELECT COUNT(*) FROM seckill_order WHERE id=%s", (TEST_ORDER_ID,))
    check(int(cur.fetchone()[0]) == 0, "B4 测试数据已清理，库存还原", f"stock={stock_before}")
    conn.close()


# ----------------------------------------------------- C. RabbitMQ 死信链路
def verify_rabbitmq_topology():
    import pika

    title("C. RabbitMQ 拓扑：一次发送扇出两条消息，延迟队列 TTL 到期转死信")

    EX = "verify.seckill.order.exchange"
    Q_MAIN = "verify.seckill.order.queue"
    Q_DELAY = "verify.seckill.order.delay.queue"
    DX = "verify.seckill.dead.exchange"
    Q_DEAD = "verify.seckill.dead.queue"
    RK = "seckill.order.create"
    DL_RK = "seckill.dead.close"
    TEST_TTL = 5000  # 真实配置是 15*60*1000（15 分钟），这里压成 5 秒便于观察

    conn = pika.BlockingConnection(pika.ConnectionParameters(
        host=HOST, port=MQ_PORT, credentials=pika.PlainCredentials("guest", "guest"), socket_timeout=5))
    ch = conn.channel()
    print(f"  目标：amqp://{HOST}:{MQ_PORT}  server={conn._impl.server_properties.get('version')}")
    print("  拓扑与 RabbitMqConfig.java 一致，仅把 TTL 临时改成 5 秒；测试后自动清理\n")

    try:
        # 清理可能残留的测试队列
        for q in (Q_MAIN, Q_DELAY, Q_DEAD):
            ch.queue_delete(q)
        for x in (EX, DX):
            ch.exchange_delete(x)

        ch.exchange_declare(EX, "direct", durable=False)
        ch.exchange_declare(DX, "direct", durable=False)
        ch.queue_declare(Q_MAIN, durable=False, arguments={
            "x-dead-letter-exchange": DX, "x-dead-letter-routing-key": DL_RK})
        ch.queue_declare(Q_DELAY, durable=False, arguments={
            "x-message-ttl": TEST_TTL, "x-dead-letter-exchange": DX, "x-dead-letter-routing-key": DL_RK})
        ch.queue_declare(Q_DEAD, durable=False)
        ch.queue_bind(Q_MAIN, EX, RK)
        ch.queue_bind(Q_DELAY, EX, RK)   # ← 与主队列同一个 routing key：扇出
        ch.queue_bind(Q_DEAD, DX, DL_RK)

        # C1 一次发布，两个队列各拿一条
        body = b'{"orderId":7653178267000000001,"note":"verify-infra"}'
        ch.basic_publish(exchange=EX, routing_key=RK, body=body, properties=pika.BasicProperties(delivery_mode=1))
        time.sleep(0.5)
        m_main = ch.queue_declare(Q_MAIN, durable=False, passive=True).method.message_count
        m_delay = ch.queue_declare(Q_DELAY, durable=False, passive=True).method.message_count
        check(m_main == 1 and m_delay == 1,
              "C1 一次 convertAndSend 扇出成两条消息（立即建单 + 延迟关单各一条）",
              f"主队列={m_main} 延迟队列={m_delay}")

        # C2 没人消费延迟队列，消息躺满 TTL 后自动转死信
        print(f"  等待 {TEST_TTL / 1000 + 1:.0f} 秒，观察 TTL 到期…")
        time.sleep(TEST_TTL / 1000 + 1)
        m_delay_2 = ch.queue_declare(Q_DELAY, durable=False, passive=True).method.message_count
        m_dead = ch.queue_declare(Q_DEAD, durable=False, passive=True).method.message_count
        check(m_delay_2 == 0 and m_dead == 1,
              "C2 延迟队列无消费者 → TTL 到期经死信交换机转入 seckill 死信队列",
              f"延迟队列={m_delay_2} 死信队列={m_dead}")
        print("      → 这一步就是「15 分钟未支付自动关单」的触发点，旧配置下永远不会发生")

        # C3 死信里的消息能被取出来（消费者能拿到 DTO）
        method, props, got = ch.basic_get(Q_DEAD, auto_ack=True)
        check(got == body, "C3 死信队列里的消息体完整可取", f"body={got}")
    finally:
        # 清理测试用的临时拓扑
        try:
            for q in (Q_MAIN, Q_DELAY, Q_DEAD):
                ch.queue_delete(q)
            for x in (EX, DX):
                ch.exchange_delete(x)
            print("\n  已清理临时交换机/队列（应用启动时会按自己的参数重新声明 seckill.* 拓扑）")
        except Exception as e:
            print(f"\n  ⚠️ 清理失败，请手动删除 verify.* 队列：{e}")
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["redis", "mysql", "mq"], help="只跑其中一段")
    args = ap.parse_args()

    t0 = time.time()
    if args.only in (None, "redis"):
        verify_redis_lua()
    if args.only in (None, "mysql"):
        verify_mysql_idempotent()
    if args.only in (None, "mq"):
        verify_rabbitmq_topology()

    title("结果")
    if failures:
        print(f"  {len(failures)} 项未通过：")
        for f in failures:
            print(f"    - {f}")
        print(f"\n  总耗时 {time.time() - t0:.1f}s")
        sys.exit(1)
    print(f"  全部通过 ✅  总耗时 {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
