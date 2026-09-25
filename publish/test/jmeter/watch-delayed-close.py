# -*- coding: utf-8 -*-
"""
延迟关单端到端观测器

持续轮询 MySQL + Redis，捕捉「15 分钟未支付自动关单」真正生效的那一刻。

判定标准（方案 A：延迟队列 TTL 到期；方案 B：定时扫表兜底 —— 二者任一或叠加触发都算通过）：
  1. seckill_order 中 status=1（未支付）的订单转为 status=3（已关闭）
  2. Redis 库存 nyw:seckill:stock:<v> 由 0 回补
  3. Redis 已下单集合 order:set 中的对应用户被移除
  4. MySQL seckill_voucher.stock 同步回补
  5. 三方对账：初始库存 - 未关闭订单数 == Redis 剩余 == DB 剩余

用法：
    python watch-delayed-close.py                      # 最多观测 20 分钟
    python watch-delayed-close.py --minutes 25
"""
import argparse
import time
from datetime import datetime

import pymysql
import redis

MYSQL = dict(host="${MW_HOST}", port=3306, user="root",
             password="${MW_PASSWORD}", database="nyw", charset="utf8mb4")
REDIS = dict(host="${MW_HOST}", port=6379, password="${MW_PASSWORD}",
             decode_responses=True)

STATUS_NAME = {1: "未支付", 2: "已支付", 3: "已关闭", 4: "已完成"}


def snapshot(v):
    r = redis.Redis(**REDIS)
    stock = r.get(f"nyw:seckill:stock:{v}")
    order_set = r.scard(f"nyw:seckill:order:set:{v}")

    conn = pymysql.connect(**MYSQL)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=%s", (v,))
            row = cur.fetchone()
            db_stock = row[0] if row else None
            cur.execute(
                "SELECT status, COUNT(*) FROM seckill_order WHERE voucher_id=%s "
                "GROUP BY status", (v,))
            dist = {st: c for st, c in cur.fetchall()}
    finally:
        conn.close()
    return dict(stock=stock, order_set=order_set, db_stock=db_stock, dist=dist)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voucher-id", type=int, default=1)
    ap.add_argument("--init-stock", type=int, default=3000)
    ap.add_argument("--minutes", type=float, default=20)
    ap.add_argument("--interval", type=float, default=15)
    args = ap.parse_args()
    v, init = args.voucher_id, args.init_stock

    deadline = time.time() + args.minutes * 60
    first = snapshot(v)
    print(f"[{datetime.now():%H:%M:%S}] 起点  Redis.stock={first['stock']} "
          f"DB.stock={first['db_stock']} order:set={first['order_set']} "
          f"状态分布={{{', '.join(f'{k}({STATUS_NAME.get(k, k)}):{c}' for k, c in sorted(first['dist'].items()))}}}",
          flush=True)

    if first["dist"].get(3, 0) > 0:
        print("  -> 关单已发生（起点即观测到已关闭订单）", flush=True)

    prev = first
    observed_change = False
    while time.time() < deadline:
        time.sleep(args.interval)
        cur_s = snapshot(v)
        changed = cur_s != prev
        if changed:
            observed_change = True
            print(f"[{datetime.now():%H:%M:%S}] 变化  Redis.stock={cur_s['stock']} "
                  f"DB.stock={cur_s['db_stock']} order:set={cur_s['order_set']} "
                  f"状态分布={{{', '.join(f'{k}({STATUS_NAME.get(k, k)}):{c}' for k, c in sorted(cur_s['dist'].items()))}}}",
                  flush=True)
        # 全部关闭且库存回补完成 -> 提前收工
        if cur_s["dist"].get(3, 0) == init and str(cur_s["stock"]) == str(init):
            print(f"\n[{datetime.now():%H:%M:%S}] ✅ 全部 {init} 单已关闭，库存已回补到 {init}，观测结束", flush=True)
            break
        prev = cur_s
    else:
        print(f"\n[{datetime.now():%H:%M:%S}] ⏱ 观测窗口结束（{'有' if observed_change else '无'}变化）", flush=True)

    final = snapshot(v)
    alive = final["dist"].get(1, 0) + final["dist"].get(2, 0)
    closed = final["dist"].get(3, 0) + final["dist"].get(4, 0)
    print("\n" + "=" * 62)
    print(f"最终：未关闭={alive}  已关闭={closed}")
    print(f"      初始库存                 = {init}")
    print(f"      初始库存 - 未关闭订单数   = {init - alive}")
    print(f"      Redis 剩余库存           = {final['stock']}")
    print(f"      MySQL 剩余库存           = {final['db_stock']}")
    print(f"      Redis order:set 成员数   = {final['order_set']}")
    ok = (init - alive) == int(final["stock"] or -1) == int(final["db_stock"] or -2)
    print(f"三方对账：{'✅ 一致' if ok else '❌ 不一致'}")
    print("=" * 62)


if __name__ == "__main__":
    main()
