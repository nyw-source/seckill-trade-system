# -*- coding: utf-8 -*-
"""
网关鉴权加固验证：确认客户端无法通过伪造身份请求头越权。

验证思路：
  网关下游挂一个回显服务，回显服务把收到的身份请求头原样吐回来，
  于是"网关照不照抄客户端身份"就有了可观测证据，不用靠读代码。

用例（debug 开关关闭，即生产姿态）：
  A1 受保护路径 + 无凭证                       → 401
  A2 受保护路径 + 伪造身份头 + 无凭证          → 401
  A3 受保护路径 + 伪造身份头 + 非法凭证        → 401
  A4 白名单路径 + 伪造身份头                   → 200 且下游收到的身份为空  ★核心
  A5 白名单路径 + 伪造身份头 + 非法凭证        → 200 且下游收到的身份为空

用例（--debug 打开 hm.auth.debug-allow-user-header=true）：
  B1 白名单路径 + 伪造身份头 + 无凭证          → 200 且下游收到该身份（本机调试可用）
  B2 受保护路径 + 伪造身份头 + 无凭证          → 200 且下游收到该身份（调试放行）

用法:
  python verify-gateway-auth.py --base http://127.0.0.1:8080 [--debug]
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

UA = "gateway-auth-verifier/1.0"


def call(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {}, method="GET")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return -1, "%s: %s" % (type(e).__name__, e)


class Checker:
    def __init__(self):
        self.p = 0
        self.f = 0

    def eq(self, name, actual, expect, extra=""):
        if actual == expect:
            self.p += 1
            print("  [PASS] %s（实际 %r）%s" % (name, actual, extra))
        else:
            self.f += 1
            print("  [FAIL] %s：期望 %r，实际 %r %s" % (name, expect, actual, extra))

    def true(self, name, cond, extra=""):
        if cond:
            self.p += 1
            print("  [PASS] %s %s" % (name, extra))
        else:
            self.f += 1
            print("  [FAIL] %s %s" % (name, extra))


def downstream_user(body):
    """从回显服务的响应里取下游真正收到的身份；返回 None 表示没收到该请求头"""
    try:
        return json.loads(body).get("x-user-id")
    except Exception:
        return "<非回显响应>"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--debug", action="store_true", help="网关已开启 debug 开关时的期望")
    args = ap.parse_args()

    c = Checker()
    base = args.base
    forged = {"X-User-Id": "999"}
    forged_bad_auth = {"X-User-Id": "999", "Authorization": "not-a-valid-token"}
    protected = base + "/orders/1"
    whitelisted = base + "/items/1"

    if not args.debug:
        print("网关鉴权加固验证（生产姿态：未开启调试开关）  base=%s" % base)

        print("\n[A1-A3] 受保护路径")
        st, _ = call(protected)
        c.eq("A1 无凭证 → 拒绝", st, 401)
        st, _ = call(protected, forged)
        c.eq("A2 伪造身份头 + 无凭证 → 拒绝", st, 401)
        st, _ = call(protected, forged_bad_auth)
        c.eq("A3 伪造身份头 + 非法凭证 → 拒绝", st, 401)

        print("\n[A4-A5] 白名单路径（★ 越权漏洞主战场）")
        st, body = call(whitelisted, forged)
        c.eq("A4 伪造身份头走白名单 → 放行", st, 200)
        c.eq("A4 下游收到的身份为空（伪造被剔除）", downstream_user(body), None)

        st, body = call(whitelisted, forged_bad_auth)
        c.eq("A5 非法凭证 + 伪造身份头走白名单 → 放行", st, 200)
        c.eq("A5 下游收到的身份为空（非法凭证不产生身份）", downstream_user(body), None)
    else:
        print("网关鉴权验证（调试姿态：已开启 hm.auth.debug-allow-user-header）  base=%s" % base)

        print("\n[B1-B2] 调试开关开启后")
        st, body = call(whitelisted, forged)
        c.eq("B1 白名单路径 + 伪造身份头 → 放行", st, 200)
        c.eq("B1 下游收到该身份（本机调试可用）", downstream_user(body), "999")

        st, body = call(protected, forged)
        c.eq("B2 受保护路径 + 伪造身份头 → 调试放行", st, 200)
        c.eq("B2 下游收到该身份", downstream_user(body), "999")

    print("\n================ 汇总 ================")
    print("PASS=%d  FAIL=%d" % (c.p, c.f))
    return 0 if c.f == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
