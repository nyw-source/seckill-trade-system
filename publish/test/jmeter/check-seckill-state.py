# -*- coding: utf-8 -*-
"""
秒杀压测 · 状态快照

一次性打印 Redis 与 MySQL 的关键状态，用于观测：
  * 压测结束后：订单数、库存
  * 延迟队列 TTL 到期后：订单状态是否由 1（未支付）变 3（已关闭）、库存是否回补

用法：
    python check-seckill-state.py
    python check-seckill-state.py --voucher-id 1
"""
import argparse

import pymysql
import redis

MYSQL = dict(host="${MW_HOST}", port=3306, user="root",
             password="${MW_PASSWORD}", database="nyw", charset="utf8mb4")
REDIS = dict(host="${MW_HOST}", port=6379, password="${MW_PASSWORD}",
             decode_responses=True)

STATUS_NAME = {1: "未支付", 2: "已支付", 3: "已关闭", 4: "已完成"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voucher-id", type=int, default=1)
    args = ap.parse_args()
    v = args.voucher_id

    print("=" * 62)
    print(f"秒杀状态快照  voucherId={v}")
    print("=" * 62)

    # ---------- Redis ----------
    r = redis.Redis(**REDIS)
    stock = r.get(f"nyw:seckill:stock:{v}")
    order_set = r.scard(f"nyw:seckill:order:set:{v}")
    order_id = r.get("nyw:id:seckill:order")
    print("\n[Redis]")
    print(f"  stock:{v:<4} = {stock}")
    print(f"  order:set:{v} 成员数 = {order_set}")
    print(f"  id:seckill:order = {order_id}")

    # ---------- MySQL ----------
    conn = pymysql.connect(**MYSQL)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=%s", (v,))
            row = cur.fetchone()
            db_stock = row[0] if row else None
            print("\n[MySQL] seckill_voucher.stock =", db_stock)

            cur.execute(
                "SELECT status, COUNT(*) FROM seckill_order WHERE voucher_id=%s "
                "GROUP BY status ORDER BY status", (v,))
            rows = cur.fetchall()
            total = sum(c for _, c in rows)
            print(f"  seckill_order 总数 = {total}")
            for st, c in rows:
                print(f"    status={st} ({STATUS_NAME.get(st, '?'):<4}) -> {c}")

            cur.execute(
                "SELECT order_id, status, create_time FROM seckill_order "
                "WHERE voucher_id=%s ORDER BY order_id LIMIT 3", (v,))
            print("  最早 3 单：", cur.fetchall())
            cur.execute(
                "SELECT order_id, status, create_time FROM seckill_order "
                "WHERE voucher_id=%s ORDER BY order_id DESC LIMIT 3", (v,))
            print("  最新 3 单：", cur.fetchall())
    finally:
        conn.close()

    # ---------- 对账 ----------
    print("\n[对账]  初始库存 3000 - 未关闭订单数 应 == Redis 剩余 == DB 剩余")
    closed = 0
    total_orders = 0
    conn = pymysql.connect(**MYSQL)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT SUM(status IN (1,2)), COUNT(*) FROM seckill_order WHERE voucher_id=%s",
                (v,))
            alive, total_orders = cur.fetchone()
            alive = alive or 0
    finally:
        conn.close()
    print(f"  未关闭订单 = {alive}   关闭订单 = {total_orders - alive}")
    print(f"  3000 - {alive} = {3000 - alive}  (期望 Redis/DB 剩余都等于此值，若已全部关闭则应为 3000)")
    print("=" * 62)


if __name__ == "__main__":
    main()
