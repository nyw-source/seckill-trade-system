#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
秒杀「延迟队列 + 扫表兜底」端到端验证（真实 Spring 服务 + 真实中间件）
====================================================================
与 verify-infra.py 的区别：verify-infra.py 不启服务、直接打中间件；
本脚本打的是**真正跑起来的 seckill-service**，验证的是完整业务链路。

覆盖的验证点：
  V1 下单成功     ：HTTP 下单 → DB 落未支付订单 + Redis 库存 -1 + 一人一单 Set 写入 + 延迟队列收到消息
  V2 延迟关单     ：延迟队列 TTL 到期 → 关单队列 → 订单被置为已关闭 + DB/Redis 库存各 +1 + 释放一人一单
  V3 重新下单     ：同一用户超时后可以重新下单（证明 srem 释放资格生效）
  V4 重复投递幂等 ：同一条关单消息投两次，库存只回补一次（面试必问）
  V5 已支付跳过   ：已支付订单收到关单消息时跳过关单、不回补库存
  V6 扫表兜底     ：手工造一条「超时但没人发关单消息」的订单，定时任务能捞出并关闭
  V7 日志证据     ：应用日志里能看到幂等跳过 / 扫表兜底 / 关单成功三类关键行

前置条件：
  1. seckill-service 已启动，例如：
     HMALL_SECKILL_ORDER_TIMEOUT_MINUTES=1 \
     HMALL_SECKILL_CLOSE_SCAN_CRON="0/20 * * * * ?" \
     java -jar seckill-service.jar   （或 IDEA 里加同样的环境变量）
     把 15 分钟压成 1 分钟，脚本才不用等 15 分钟。
  2. 本机有 JDK：先编译消息体生成器
     javac -cp seckill-service/target/classes -d test/e2e/bin test/e2e/DumpDto.java
  3. python -m pip install redis pymysql pika

用法：
    python test/e2e/verify-seckill-e2e.py --app-log /path/to/seckill.log
"""

import argparse
import base64
import json
import pathlib
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

HOST = "${MW_HOST}"
REDIS_PORT = 6379
MYSQL_PORT = 3306
MQ_PORT = 5672
PASSWORD = "${MW_PASSWORD}"
DB = "nyw"

VOUCHER_ID = 1
ITEM_ID = 1
INIT_STOCK = 3000

STOCK_KEY = "nyw:seckill:stock:1"
ORDER_SET_KEY = "nyw:seckill:order:set:1"

EXCHANGE = "seckill.order.close.exchange"
ROUTING_KEY = "order.close"
QUEUE = "seckill.order.close.queue"
DELAY_QUEUE = "seckill.order.delay.queue"

ROOT = pathlib.Path(__file__).resolve().parents[2]

OK = "  \033[32m[PASS]\033[0m"
NO = "  \033[31m[FAIL]\033[0m"
SKIP = "  \033[33m[SKIP]\033[0m"
failures = []


def check(cond, msg, extra=""):
    print(f"{OK if cond else NO} {msg}" + (f"  {extra}" if extra else ""))
    if not cond:
        failures.append(msg)
    return cond


def title(t):
    print(f"\n{'=' * 74}\n{t}\n{'=' * 74}")


# ----------------------------------------------------------------- 基础工具
def http(method, path, user_id=None, timeout=15):
    """返回 (status, body_text)；4xx/5xx 不抛异常，便于断言错误分支"""
    req = urllib.request.Request(f"{BASE_URL}{path}", method=method)
    if user_id is not None:
        req.add_header("X-User-Id", str(user_id))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def order_id_from(body):
    """接口返回的 Long 被 JsonConfig 序列化成了字符串，这里统一转成 int"""
    try:
        return int(json.loads(body))
    except Exception:
        return int(body.strip().strip('"'))


def dto_bytes(order_id, voucher_id=VOUCHER_ID, user_id=30001, item_id=ITEM_ID):
    """用项目自己的 DTO 类生成 Java 序列化消息体（与 SimpleMessageConverter 的格式一致）"""
    out = subprocess.run(
        ["java", "-cp", f"test/e2e/bin;seckill-service/target/classes", "e2e.DumpDto",
         str(order_id), str(voucher_id), str(user_id), str(item_id)],
        cwd=str(ROOT), capture_output=True, check=True)
    return base64.b64decode(out.stdout.decode().strip())


def queue_ttl_ms(queue):
    """从 RabbitMQ 管理 API 读取队列的 x-message-ttl（拿不到就按 15 分钟算）"""
    try:
        req = urllib.request.Request(
            f"http://{HOST}:15672/api/queues/%2F/{queue}?columns=arguments")
        req.add_header("Authorization",
                       "Basic " + base64.b64encode(b"guest:guest").decode())
        with urllib.request.urlopen(req, timeout=6) as resp:
            args = json.loads(resp.read().decode()).get("arguments") or {}
            ttl = args.get("x-message-ttl")
            if ttl:
                return int(ttl)
    except Exception as e:
        print(f"  （读取队列 TTL 失败，按 15 分钟估算：{e}）")
    return 15 * 60 * 1000


def main():
    global BASE_URL
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8085")
    ap.add_argument("--app-log", default=None, help="应用日志文件，用于 V7 日志证据核对")
    args = ap.parse_args()
    BASE_URL = args.base_url

    import pymysql
    import redis
    import pika

    r = redis.Redis(host=HOST, port=REDIS_PORT, password=PASSWORD, decode_responses=True)
    db = pymysql.connect(host=HOST, port=MYSQL_PORT, user="root", password=PASSWORD,
                         database=DB, autocommit=True)
    cur = db.cursor()
    mq = pika.BlockingConnection(pika.ConnectionParameters(
        host=HOST, port=MQ_PORT, credentials=pika.PlainCredentials("guest", "guest"),
        socket_timeout=5))
    ch = mq.channel()

    def publish_close(order_id, user_id):
        ch.basic_publish(exchange=EXCHANGE, routing_key=ROUTING_KEY,
                         body=dto_bytes(order_id, user_id=user_id),
                         properties=pika.BasicProperties(
                             content_type="application/x-java-serialized-object",
                             delivery_mode=2))

    def db_stock():
        cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=%s", (VOUCHER_ID,))
        return int(cur.fetchone()[0])

    def db_status(order_id):
        cur.execute("SELECT status FROM seckill_order WHERE id=%s", (order_id,))
        row = cur.fetchone()
        return int(row[0]) if row else None

    def redis_stock():
        return int(r.get(STOCK_KEY) or 0)

    t0 = time.time()

    # ------------------------------------------------------------ 前置检查
    title("前置检查：服务 / 中间件 / 秒杀券")
    status, body = http("GET", "/seckill/item/1")
    check(status in (200, 404, 500), "seckill-service 有响应（能连上 Tomcat）", f"HTTP {status}")
    status, _ = http("POST", "/seckill/preheat/1")
    check(status == 200, "调用 POST /seckill/preheat/1 预热成功", f"HTTP {status}")

    ttl = queue_ttl_ms(DELAY_QUEUE)
    print(f"  延迟队列 {DELAY_QUEUE} 的 x-message-ttl = {ttl} ms"
          f"（{'生产默认 15 分钟' if ttl > 60000 else '联调压短值，脚本按它等待'}）")

    # 清场：DB / Redis 恢复初始状态
    cur.execute("DELETE FROM seckill_order WHERE voucher_id=%s", (VOUCHER_ID,))
    cur.execute("UPDATE seckill_voucher SET stock=%s WHERE voucher_id=%s", (INIT_STOCK, VOUCHER_ID))
    r.set(STOCK_KEY, INIT_STOCK)
    r.delete(ORDER_SET_KEY)
    # 清空队列里的历史消息
    for q in (DELAY_QUEUE, QUEUE):
        ch.queue_purge(q)
    check(db_stock() == INIT_STOCK and redis_stock() == INIT_STOCK,
          "DB / Redis 库存已复位", f"DB={db_stock()} Redis={redis_stock()}")

    # 注意：这几个测试订单号必须 < 9223372036854775807（BIGINT 有符号上限），
    # 用 99 开头的 19 位数会直接报 DataError 1264 Out of range
    user_a = 20001
    order_a = 7000000000000000001

    # ------------------------------------------------------------ V1 下单
    title("V1 下单成功：DB 落单 + Redis 扣减 + 一人一单 + 延迟消息入队")
    status, body = http("POST", f"/seckill/{VOUCHER_ID}", user_id=user_a)
    check(status == 200, "HTTP 下单返回 200", f"HTTP {status} body={body[:80]}")
    order_a = order_id_from(body) if status == 200 else order_a
    print(f"  生成的秒杀订单号：{order_a}（{len(str(order_a))} 位）")

    time.sleep(2)
    check(db_status(order_a) == 1, "DB 里生成了「未支付」订单（status=1）", f"status={db_status(order_a)}")
    check(redis_stock() == INIT_STOCK - 1, "Redis 库存 -1",
          f"{INIT_STOCK} → {redis_stock()}")
    check(r.sismember(ORDER_SET_KEY, user_a), "一人一单 Set 中已记录该用户")
    delay_msgs = ch.queue_declare(queue=DELAY_QUEUE, passive=True).method.message_count
    check(delay_msgs >= 1, "延迟队列里已有「待关单」消息（15 分钟后触发）", f"消息数={delay_msgs}")

    # ------------------------------------------------------------ V2 延迟关单
    wait_s = int((ttl or 60000) / 1000) + 25
    title(f"V2 延迟关单：等待延迟队列 TTL 到期（最多 {wait_s}s）")
    deadline = time.time() + wait_s
    while time.time() < deadline and db_status(order_a) != 3:
        time.sleep(2)
    st = db_status(order_a)
    check(st == 3, "订单被自动关闭（status=3）", f"status={st}")
    check(db_stock() == INIT_STOCK, "DB 库存已回补", f"stock={db_stock()}")
    check(redis_stock() == INIT_STOCK, "Redis 库存已回补", f"stock={redis_stock()}")
    check(not r.sismember(ORDER_SET_KEY, user_a), "一人一单资格已释放（srem 生效）")

    # ------------------------------------------------------------ V3 重新下单
    title("V3 超时用户重新下单：资格释放后可再次参与")
    status, body = http("POST", f"/seckill/{VOUCHER_ID}", user_id=user_a)
    check(status == 200, "同一用户超时后能再次下单成功", f"HTTP {status} body={body[:80]}")
    order_a2 = order_id_from(body) if status == 200 else 0
    if order_a2:
        print(f"  新订单号：{order_a2}")
        # 关键：下单是异步建单，必须等消费者把订单落库、把 DB 库存扣掉之后再手工收尾，
        # 否则下面的清理会和消费者的 deductStock 抢时间，导致后续断言读到错位的库存
        time.sleep(2)
        cur.execute("UPDATE seckill_order SET status=3 WHERE id=%s", (order_a2,))
        cur.execute("UPDATE seckill_voucher SET stock=stock+1 WHERE voucher_id=%s", (VOUCHER_ID,))
        r.incr(STOCK_KEY)
        r.srem(ORDER_SET_KEY, user_a)
        print("  已手工收尾该订单（status=3、库存与一人一单还原），避免它干扰后续断言")

    # ------------------------------------------------------------ V4 重复投递幂等
    title("V4 ★重复投递同一条关单消息：库存只回补一次")
    order_dup, user_dup = 7111111111111111111, 30001
    cur.execute("UPDATE seckill_voucher SET stock=2000 WHERE voucher_id=%s", (VOUCHER_ID,))
    r.set(STOCK_KEY, 2000)
    r.sadd(ORDER_SET_KEY, user_dup)
    cur.execute("DELETE FROM seckill_order WHERE id=%s", (order_dup,))
    cur.execute("""INSERT INTO seckill_order (id, user_id, voucher_id, item_id, status, create_time)
                   VALUES (%s, %s, %s, %s, 1, NOW())""", (order_dup, user_dup, VOUCHER_ID, ITEM_ID))

    stock0, redis0 = db_stock(), redis_stock()
    publish_close(order_dup, user_dup)
    time.sleep(3)
    check(db_stock() == stock0 + 1, "第 1 次投递：DB 库存 +1", f"{stock0} → {db_stock()}")
    check(redis_stock() == redis0 + 1, "第 1 次投递：Redis 库存同步 +1", f"{redis0} → {redis_stock()}")
    check(not r.sismember(ORDER_SET_KEY, user_dup), "第 1 次投递：一人一单资格被释放")

    publish_close(order_dup, user_dup)   # 同一条消息再投一次
    publish_close(order_dup, user_dup)   # 再投一次
    time.sleep(3)
    check(db_stock() == stock0 + 1, "★重复投递 2 次：DB 库存不再变化（CAS 0 行幂等）", f"stock={db_stock()}")
    check(redis_stock() == redis0 + 1, "★重复投递 2 次：Redis 库存不再变化", f"stock={redis_stock()}")
    check(db_status(order_dup) == 3, "订单状态仍为「已关闭」", f"status={db_status(order_dup)}")

    # ------------------------------------------------------------ V5 已支付跳过
    title("V5 已支付订单收到关单消息：跳过关单、不回补库存")
    order_paid, user_paid = 7222222222222222222, 30002
    cur.execute("DELETE FROM seckill_order WHERE id=%s", (order_paid,))
    cur.execute("""INSERT INTO seckill_order (id, user_id, voucher_id, item_id, status, create_time, pay_time)
                   VALUES (%s, %s, %s, %s, 2, NOW() - INTERVAL 20 MINUTE, NOW())""",
                (order_paid, user_paid, VOUCHER_ID, ITEM_ID))
    stock_before_paid = db_stock()
    redis_before_paid = redis_stock()

    publish_close(order_paid, user_paid)
    time.sleep(3)
    check(db_status(order_paid) == 2, "已支付订单未被误关闭（status 仍为 2）", f"status={db_status(order_paid)}")
    check(db_stock() == stock_before_paid, "DB 库存未被回补", f"stock={db_stock()}")
    check(redis_stock() == redis_before_paid, "Redis 库存未被回补", f"stock={redis_stock()}")

    # ------------------------------------------------------------ V6 扫表兜底
    title("V6 扫表兜底：造一条「没人发关单消息」的超时订单，等定时任务捞")
    order_scan, user_scan = 7333333333333333333, 30003
    cur.execute("DELETE FROM seckill_order WHERE id=%s", (order_scan,))
    cur.execute("""INSERT INTO seckill_order (id, user_id, voucher_id, item_id, status, create_time)
                   VALUES (%s, %s, %s, %s, 1, NOW() - INTERVAL 20 MINUTE)""",
                (order_scan, user_scan, VOUCHER_ID, ITEM_ID))
    r.sadd(ORDER_SET_KEY, user_scan)
    stock_before_scan = db_stock()
    redis_before_scan = redis_stock()

    deadline = time.time() + 90
    while time.time() < deadline and db_status(order_scan) != 3:
        time.sleep(3)
    check(db_status(order_scan) == 3, "定时扫表任务把超时订单关掉了", f"status={db_status(order_scan)}")
    check(db_stock() == stock_before_scan + 1, "扫表关单同样回补了 DB 库存",
          f"{stock_before_scan} → {db_stock()}")
    check(redis_stock() == redis_before_scan + 1, "扫表关单同样回补了 Redis 库存",
          f"{redis_before_scan} → {redis_stock()}")
    check(not r.sismember(ORDER_SET_KEY, user_scan), "扫表关单同样释放了一人一单资格")

    # ------------------------------------------------------------ V7 日志证据
    title("V7 应用日志证据")
    if args.app_log and pathlib.Path(args.app_log).exists():
        raw = pathlib.Path(args.app_log).read_bytes()
        log = None
        for enc in ("utf-8", "gbk", "cp936"):
            try:
                log = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        if log is None:
            log = raw.decode("utf-8", "replace")
        for kw, desc in [("订单不存在或已处理，跳过关闭（幂等）", "幂等跳过（重复投递被拦住）"),
                         ("扫表发现", "扫表兜底任务真的扫到了超时单"),
                         ("超时订单已关闭", "关单成功"),
                         ("Redis 库存与一人一单资格已回补", "事务提交后回补 Redis")]:
            check(kw in log, f"日志中出现「{desc}」", f"关键字：{kw}")
    else:
        print(f"{SKIP} 未提供 --app-log，跳过日志核对")

    # ------------------------------------------------------------ 收尾
    title("收尾：清理验证数据，恢复到压测前置状态")
    cur.execute("DELETE FROM seckill_order WHERE voucher_id=%s", (VOUCHER_ID,))
    cur.execute("UPDATE seckill_voucher SET stock=%s WHERE voucher_id=%s", (INIT_STOCK, VOUCHER_ID))
    r.set(STOCK_KEY, INIT_STOCK)
    r.delete(ORDER_SET_KEY)
    for q in (DELAY_QUEUE, QUEUE):
        ch.queue_purge(q)
    check(db_stock() == INIT_STOCK and redis_stock() == INIT_STOCK and not r.exists(ORDER_SET_KEY),
          "DB/Redis 已还原成压测前置状态（stock=3000、下单集合清空）",
          f"DB={db_stock()} Redis={redis_stock()}")

    mq.close()
    db.close()

    print(f"\n{'=' * 74}")
    if failures:
        print(f"  {len(failures)} 项未通过：")
        for f in failures:
            print(f"    - {f}")
        print(f"  总耗时 {time.time() - t0:.1f}s")
        sys.exit(1)
    print(f"  全部通过 ✅  总耗时 {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
