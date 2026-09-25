# -*- coding: utf-8 -*-
"""
秒杀压测 · 结果验收

两件事：
  A. 解析 JMeter 的 result.jtl —— 成功率、QPS、RT 分位、失败原因分布
  B. 校验数据一致性 —— 「零超卖」的硬证据

为什么要先轮询等待：下单是异步的。HTTP 接口只负责
「Redis Lua 扣减 + 投递 MQ」就返回了，真正落库发生在消费者 createSeckillOrder 里。
压测一结束就查库，会看到远小于预期的订单数，误判成「丢单」。

用法：
    python verify-seckill-loadtest.py --jtl result.jtl --expect 3000
"""
import argparse
import collections
import csv
import sys
import time

import pymysql
import redis

DEFAULT_MYSQL = dict(host="${MW_HOST}", port=3306, user="root",
                     password="${MW_PASSWORD}", database="nyw", charset="utf8mb4")
DEFAULT_REDIS = dict(host="${MW_HOST}", port=6379, password="${MW_PASSWORD}",
                     decode_responses=True)

PASS, FAIL = "[PASS]", "[FAIL]"
results = []


def check(name, ok, detail=""):
    results.append((ok, name, detail))
    print("  %s %-42s %s" % (PASS if ok else FAIL, name, detail))
    return ok


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def analyze_jtl(path):
    print("=== A. JMeter 结果分析 (%s) ===" % path)
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print("  [FAIL] result.jtl 为空")
        return None

    total = len(rows)
    ok_rows = [r for r in rows if r["success"] == "true"]
    elapsed = sorted(int(r["elapsed"]) for r in rows)
    ok_elapsed = sorted(int(r["elapsed"]) for r in ok_rows)

    ts = [int(r["timeStamp"]) for r in rows]
    span = (max(ts) - min(ts)) / 1000.0
    print("  样本总数            : %d" % total)
    print("  成功率              : %.2f%%  (%d 成功 / %d 失败)"
          % (len(ok_rows) * 100.0 / total, len(ok_rows), total - len(ok_rows)))
    print("  时间跨度            : %.1f s" % span)
    if span > 0:
        print("  平均吞吐            : %.1f req/s" % (total / span))
    print("  RT 平均 / P50 / P90 / P95 / P99 / Max : "
          "%d / %d / %d / %d / %d / %d ms"
          % (sum(elapsed) / total, pct(elapsed, .50), pct(elapsed, .90),
             pct(elapsed, .95), pct(elapsed, .99), elapsed[-1]))
    if ok_elapsed:
        print("  成功请求 RT 平均/P99: %d / %d ms"
              % (sum(ok_elapsed) / len(ok_elapsed), pct(ok_elapsed, .99)))

    print("  响应码分布：")
    for code, n in collections.Counter(r["responseCode"] for r in rows).most_common(8):
        print("    %-56s %d" % (code[:56], n))
    if total - len(ok_rows):
        print("  失败原因分布：")
        for msg, n in collections.Counter(r["responseMessage"] for r in rows
                                          if r["success"] != "true").most_common(8):
            print("    %-56s %d" % (msg[:56], n))

    check("压测请求全部为 HTTP 200", len(ok_rows) == total,
          "%d/%d" % (len(ok_rows), total))
    return dict(total=total, ok=len(ok_rows), elapsed=elapsed, span=span)


def wait_orders(cur, expect, timeout=180):
    """下单是异步的，轮询等待 MQ 消费完（或超时）。"""
    print("  … 等待异步建单完成（最多 %ds）" % timeout)
    t0 = time.time()
    last = -1
    while time.time() - t0 < timeout:
        cur.execute("SELECT COUNT(*) FROM seckill_order")
        n = cur.fetchone()[0]
        if n != last:
            print("     已落库 %d / %d 单" % (n, expect))
            last = n
        if n >= expect:
            print("     达成预期，用时 %.1fs" % (time.time() - t0))
            return n
        time.sleep(2)
    print("     超时，最终 %d 单" % last)
    return last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jtl", default="")
    ap.add_argument("--voucher-id", type=int, default=1)
    ap.add_argument("--expect", type=int, default=3000, help="预期成功订单数")
    ap.add_argument("--stock", type=int, default=0,
                    help="压测前初始库存。默认等于 --expect（即库存恰好被抢光）；"
                         "若中间有网关限流把请求挡在外面，库存不会被抢完，需显式传初始库存")
    ap.add_argument("--wait-timeout", type=int, default=180)
    args = ap.parse_args()
    if args.stock <= 0:
        args.stock = args.expect

    v = args.voucher_id
    stock_key = "nyw:seckill:stock:%d" % v
    set_key = "nyw:seckill:order:set:%d" % v

    jtl = analyze_jtl(args.jtl) if args.jtl else None
    if jtl:
        print()

    r = redis.Redis(**DEFAULT_REDIS)
    c = pymysql.connect(**DEFAULT_MYSQL)
    cur = c.cursor()

    print("=== B. 数据一致性验收（零超卖）===")
    order_cnt = wait_orders(cur, args.expect, args.wait_timeout)
    print()

    cur.execute("SELECT COUNT(*) FROM seckill_order")
    order_cnt = cur.fetchone()[0]
    cur.execute("SELECT COUNT(DISTINCT user_id) FROM seckill_order")
    distinct_users = cur.fetchone()[0]
    cur.execute("SELECT status, COUNT(*) FROM seckill_order GROUP BY status")
    status_dist = dict(cur.fetchall())
    cur.execute("SELECT stock FROM seckill_voucher WHERE voucher_id=%s", (v,))
    db_stock = cur.fetchone()[0]
    cur.execute("SELECT MIN(user_id), MAX(user_id) FROM seckill_order")
    uid_range = cur.fetchone()

    redis_stock = r.get(stock_key)
    redis_card = r.scard(set_key)

    print("  MySQL  seckill_order 订单数      = %d" % order_cnt)
    print("  MySQL  去重 userId 数            = %d" % distinct_users)
    print("  MySQL  状态分布                  = %s" % status_dist)
    print("  MySQL  秒杀券剩余 DB 库存          = %d" % db_stock)
    print("  MySQL  userId 区间               = %s" % (uid_range,))
    print("  Redis  剩余库存 %-22s = %s" % (stock_key, redis_stock))
    print("  Redis  已下单集合 %-20s = %s" % (set_key, redis_card))
    print()

    # 核心断言
    remaining = args.stock - args.expect
    print("  预期剩余库存 = 初始库存 %d - 成功单数 %d = %d" % (args.stock, args.expect, remaining))
    print()
    check("订单数 == 预期成功数", order_cnt == args.expect,
          "%d / %d" % (order_cnt, args.expect))
    check("零超卖：订单数 <= 初始库存", order_cnt <= args.stock,
          "%d <= %d" % (order_cnt, args.stock))
    check("一人一单：去重 userId == 订单数", distinct_users == order_cnt == args.expect,
          "%d 个不同用户" % distinct_users)
    check("库存扣减精确：初始库存 - 单数 == Redis 剩余库存",
          str(remaining) == redis_stock,
          "%d - %d = %s" % (args.stock, args.expect, redis_stock))
    check("Redis 已下单集合 == 订单数", redis_card == args.expect,
          "SCARD=%s" % redis_card)
    check("DB 库存与 Redis 口径一致", str(db_stock) == redis_stock,
          "redis=%s db=%s" % (redis_stock, db_stock))
    check("全部订单处于未支付状态(1)", status_dist.get(1, 0) == args.expect,
          str(status_dist))

    c.close()

    failed = [n for ok, n, _ in results if not ok]
    print()
    print("=" * 78)
    if failed:
        print("验收未通过，%d 项不满足：" % len(failed))
        for n in failed:
            print("   - " + n)
        sys.exit(1)
    print("验收通过：%d 项断言全部满足 —— 3000 并发下零超卖成立" % len(results))


if __name__ == "__main__":
    main()
