# -*- coding: utf-8 -*-
"""
item-service 商品详情缓存（CacheService 接入）端到端验证

核心手法：不改代码、不加埋点，只数**应用日志里真实执行的 SQL 条数**
（MyBatis 会打印 `==>  Preparing:` 与 `==> Parameters: <id>(Long)`）。
缓存命中 → 该次请求不产生 SQL；缓存未命中 → 恰好 1 条 SQL。

验证项：
  V1 首次查询（缓存未命中）        → 1 条 SQL，且缓存被回填
  V2 第二次查询（缓存命中）        → 0 条 SQL
  V3 查询不存在的商品两次（防穿透）→ 第 1 次 1 条 SQL，第 2 次 0 条（空值缓存生效）
  V4 更新商品后再次查询（Cache-Aside 一致性）→ 重新产生 1 条 SQL（缓存被失效）

用法:
  python verify-item-cache.py --base http://127.0.0.1:8081 --log /tmp/item8081.log
"""
import argparse
import io
import json
import os
import sys
import time
import urllib.request

NON_EXIST_ID = 999999999999


def http(method, url, body=None, timeout=15):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    # 关键：绕开会话代理，否则 127.0.0.1 也会被送去代理，报 502
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "ignore")
            return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")


def read_log(path, offset):
    """Windows 控制台日志可能是 GBK，也可能有 UTF-8，逐个编码兜底"""
    with open(path, "rb") as f:
        f.seek(offset)
        raw = f.read()
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "ignore")


def _count_param_lines(text, key):
    """统计日志片段里针对某个 id 的参数绑定行，等价于该 id 真实执行的 SQL 条数"""
    tag = "Parameters: %s" % key
    return sum(1 for line in text.splitlines() if tag in line)


class Checker:
    def __init__(self):
        self.pass_n = 0
        self.fail_n = 0

    def check(self, name, actual, expect, extra=""):
        ok = actual == expect
        if ok:
            self.pass_n += 1
            print("  [PASS] %s（实际 %s）%s" % (name, actual, extra))
        else:
            self.fail_n += 1
            print("  [FAIL] %s：期望 %s，实际 %s %s" % (name, expect, actual, extra))
        return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8081")
    ap.add_argument("--log", required=True)
    args = ap.parse_args()

    c = Checker()
    print("item-service 商品缓存端到端验证  base=%s" % args.base)

    # ---- 准备：拿一个真实存在的商品 id ----
    st, body = http("GET", "%s/items/page?pageNo=1&pageSize=1" % args.base)
    if st != 200:
        print("无法访问 %s（http=%s）" % (args.base, st))
        return 1
    item = json.loads(body)["list"][0]
    item_id = int(item["id"])
    print("测试商品 id=%s name=%s" % (item_id, item["name"][:20]))

    # ---- V1/V2：缓存回填 + 命中 ----
    print("\n[V1/V2] 缓存回填与命中")
    off = os.path.getsize(args.log)
    http("PUT", "%s/items" % args.base,
         {"id": item_id, "price": item["price"], "stock": item["stock"]})  # 触发 evict
    time.sleep(0.5)

    off = os.path.getsize(args.log)
    st1, b1 = http("GET", "%s/items/%s" % (args.base, item_id))
    time.sleep(0.8)
    c.check("V1 首次查询请求成功", st1, 200)
    c.check("V1 首次查询回源 SQL 条数", _count_param_lines(read_log(args.log, off), item_id), 1)

    off = os.path.getsize(args.log)
    st2, b2 = http("GET", "%s/items/%s" % (args.base, item_id))
    time.sleep(0.8)
    c.check("V2 二次查询请求成功", st2, 200)
    c.check("V2 二次查询未打 DB（缓存命中）", _count_param_lines(read_log(args.log, off), item_id), 0)
    c.check("V2 两次返回内容一致", b1 == b2 and len(b1) > 0, True)

    # ---- V3：防穿透（空值缓存） ----
    print("\n[V3] 缓存穿透保护（不存在的商品 id）")
    off = os.path.getsize(args.log)
    st3, _ = http("GET", "%s/items/%s" % (args.base, NON_EXIST_ID))
    time.sleep(0.8)
    c.check("V3 首次查询不存在商品返回 200", st3, 200)
    c.check("V3 首次确实回源 1 条 SQL", _count_param_lines(read_log(args.log, off), NON_EXIST_ID), 1)

    off = os.path.getsize(args.log)
    http("GET", "%s/items/%s" % (args.base, NON_EXIST_ID))
    time.sleep(0.8)
    c.check("V3 二次查询被空值缓存挡住（0 条 SQL）",
            _count_param_lines(read_log(args.log, off), NON_EXIST_ID), 0)

    # ---- V4：写路径失效缓存 ----
    print("\n[V4] Cache-Aside：更新商品后缓存失效")
    # 先保证缓存里有值
    http("GET", "%s/items/%s" % (args.base, item_id))
    time.sleep(0.5)
    off = os.path.getsize(args.log)
    http("GET", "%s/items/%s" % (args.base, item_id))
    time.sleep(0.8)
    c.check("V4 前置：更新前查询命中缓存（0 条 SQL）",
            _count_param_lines(read_log(args.log, off), item_id), 0)

    st4, _ = http("PUT", "%s/items" % args.base,
                  {"id": item_id, "price": item["price"], "stock": item["stock"]})
    time.sleep(0.8)
    c.check("V4 更新商品成功", st4, 200)

    off = os.path.getsize(args.log)
    http("GET", "%s/items/%s" % (args.base, item_id))
    time.sleep(0.8)
    c.check("V4 更新后查询重新回源（缓存已失效）",
            _count_param_lines(read_log(args.log, off), item_id), 1)

    print("\n================ 汇总 ================")
    print("PASS=%d  FAIL=%d" % (c.pass_n, c.fail_n))
    return 0 if c.fail_n == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
