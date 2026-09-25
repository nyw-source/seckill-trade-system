# -*- coding: utf-8 -*-
"""
秒杀压测 · 数据准备

把 Redis 与 MySQL 复位到「3000 张券、0 订单」的干净起点，保证压测可重复执行。

关键点：
  * Redis 有三个键要清：库存 stock、已下单用户集合 order:set、订单 ID 自增 id:seckill:order
    （第三个不清会出现订单 ID 序号延续，虽不影响正确性，但让每次压测的 ID 段不固定）
  * MySQL 侧 seckill_order 必须清空，且 seckill_voucher.stock 要复位 ——
    createSeckillOrder 里除了 Redis 扣减，还会执行 `UPDATE ... stock = stock - 1` 扣数据库库存，
    不复位的话第二轮压测会因 DB 库存不足而回滚订单。
  * stock 键必须先 DEL 再 SET：preheatStock 用的是 setIfAbsent，
    如果键已存在（比如上轮压测扣到了 0），它不会覆盖，压测就会直接「库存不足」。

用法：
    python prepare-seckill-loadtest.py                 # 按默认值复位
    python prepare-seckill-loadtest.py --stock 3000 --voucher-id 1
"""
import argparse
import sys

import pymysql
import redis

DEFAULT_MYSQL = dict(host="${MW_HOST}", port=3306, user="root",
                     password="${MW_PASSWORD}", database="nyw", charset="utf8mb4")
DEFAULT_REDIS = dict(host="${MW_HOST}", port=6379, password="${MW_PASSWORD}",
                     decode_responses=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voucher-id", type=int, default=1)
    ap.add_argument("--stock", type=int, default=3000)
    ap.add_argument("--mysql-host", default=DEFAULT_MYSQL["host"])
    ap.add_argument("--redis-host", default=DEFAULT_REDIS["host"])
    args = ap.parse_args()

    v = args.voucher_id
    stock_key = "nyw:seckill:stock:%d" % v
    set_key = "nyw:seckill:order:set:%d" % v
    id_key = "nyw:id:seckill:order"

    problems = []

    # ---------- 1. Redis ----------
    r = redis.Redis(**DEFAULT_REDIS)
    r.ping()
    before_stock = r.get(stock_key)
    before_card = r.scard(set_key)
    print("=== Redis 复位 ===")
    print("  复位前：stock=%-8s order:set=%s" % (before_stock, before_card))

    r.delete(stock_key, set_key, id_key)
    r.set(stock_key, args.stock)
    print("  复位后：stock=%-8s order:set=%s  (已删除 %s)"
          % (r.get(stock_key), r.scard(set_key), id_key))

    # ---------- 2. MySQL ----------
    c = pymysql.connect(**{**DEFAULT_MYSQL, "host": args.mysql_host})
    cur = c.cursor()
    print("=== MySQL 复位 ===")

    cur.execute("SELECT voucher_id, item_id, stock, begin_time, end_time "
                "FROM seckill_voucher WHERE voucher_id=%s", (v,))
    row = cur.fetchone()
    if row is None:
        print("  [FAIL] seckill_voucher 中不存在 voucher_id=%d" % v)
        sys.exit(1)
    _, item_id, db_stock, begin_time, end_time = row
    print("  券信息：voucher_id=%d item_id=%s 原库存=%s 有效期=%s ~ %s"
          % (v, item_id, db_stock, begin_time, end_time))

    cur.execute("SELECT NOW()")
    now = cur.fetchone()[0]
    if not (begin_time <= now <= end_time):
        problems.append("当前时间 %s 不在秒杀时间窗 %s ~ %s 内，所有请求会被拒（秒杀尚未开始/已结束）"
                        % (now, begin_time, end_time))
        print("  [FAIL] 时间窗校验不通过：now=%s 不在 %s ~ %s 内" % (now, begin_time, end_time))
    else:
        print("  [ OK ] 时间窗校验通过：now=%s ∈ [%s, %s]" % (now, begin_time, end_time))

    cur.execute("DELETE FROM seckill_order WHERE voucher_id=%s", (v,))
    deleted = cur.rowcount
    cur.execute("UPDATE seckill_voucher SET stock=%s WHERE voucher_id=%s", (args.stock, v))
    c.commit()
    print("  已删除 seckill_order 记录 %d 行；seckill_voucher.stock 复位为 %d" % (deleted, args.stock))

    cur.execute("SELECT COUNT(*) FROM seckill_order")
    order_cnt = cur.fetchone()[0]
    cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=%s", (v,))
    db_now = cur.fetchone()[0]
    c.close()

    # ---------- 3. 汇总 ----------
    print("=== 复位结果 ===")
    print("  Redis %-28s = %s" % (stock_key, r.get(stock_key)))
    print("  Redis %-28s = %s" % (set_key, r.scard(set_key)))
    print("  MySQL seckill_order 总行数        = %s" % order_cnt)
    print("  MySQL seckill_voucher.stock       = %s" % db_now)

    if order_cnt != 0:
        problems.append("seckill_order 未清空，仍有 %d 行" % order_cnt)
    if str(db_now) != str(args.stock):
        problems.append("DB 库存复位失败：%s != %s" % (db_now, args.stock))

    print()
    if problems:
        print("!! 有 %d 项阻塞，请先处理：" % len(problems))
        for p in problems:
            print("   - " + p)
        sys.exit(1)
    print(">> 数据准备完成，可以开始压测（目标库存 %d，预期成功 %d 单）" % (args.stock, args.stock))


if __name__ == "__main__":
    main()
