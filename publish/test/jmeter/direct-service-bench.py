# -*- coding: utf-8 -*-
"""
秒杀服务 · 直连基准（绕过网关）

目的：把「服务端真实处理能力」与「网关 + JMeter 客户端自身开销」分开测。

背景：用 JMeter 在同机跑 3000 线程时，客户端自己就要吃掉大量 CPU 和内存，
测出来的响应时间被客户端干扰污染，无法判断到底是服务端慢还是压测机慢。
asyncio 单进程事件驱动可以在一台机器上轻松维持数千并发连接，
客户端开销极低，因此更适合做「服务端能力」的定点测量。

身份传递方式：seckill-service 的 UserInfoInterceptor 读 X-User-Id 头
（生产环境由网关鉴权后覆写注入；这里直连所以自己带），
因此本脚本等价于「网关放行后打服务」的那一段。

用法：
    python direct-service-bench.py --concurrency 3000 --total 3000
"""
import argparse
import asyncio
import collections
import statistics
import sys
import time


class Recorder:
    def __init__(self):
        self.lat = []
        self.codes = collections.Counter()
        self.errs = collections.Counter()

    def add(self, ms, code):
        self.lat.append(ms)
        self.codes[code] += 1


async def one(host, port, path, uid, rec, timeout):
    t0 = time.perf_counter()
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(host, port), timeout)
        req = (
            "POST %s HTTP/1.1\r\n"
            "Host: %s:%d\r\n"
            "X-User-Id: %d\r\n"
            "Content-Length: 0\r\n"
            "Connection: close\r\n\r\n" % (path, host, port, uid)
        )
        writer.write(req.encode())
        await asyncio.wait_for(writer.drain(), timeout)
        raw = await asyncio.wait_for(reader.read(-1), timeout)
        ms = (time.perf_counter() - t0) * 1000
        first = raw.split(b"\r\n", 1)[0].decode("latin-1") if raw else ""
        # HTTP/1.1 200 OK  /  HTTP/1.1 400 Bad Request
        parts = first.split()
        code = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else 0
        rec.add(ms, code)
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
    except asyncio.TimeoutError:
        rec.add((time.perf_counter() - t0) * 1000, -1)
        rec.errs["timeout"] += 1
    except Exception as e:
        rec.add((time.perf_counter() - t0) * 1000, -2)
        rec.errs[type(e).__name__] += 1


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8085)
    ap.add_argument("--voucher-id", type=int, default=1)
    ap.add_argument("--concurrency", type=int, default=3000, help="同时在途请求数")
    ap.add_argument("--total", type=int, default=3000, help="总请求数")
    ap.add_argument("--start-user", type=int, default=100001)
    ap.add_argument("--timeout", type=float, default=30.0)
    args = ap.parse_args()

    path = "/seckill/%d" % args.voucher_id
    sem = asyncio.Semaphore(args.concurrency)
    rec = Recorder()
    done = 0
    t0 = time.perf_counter()

    async def worker(uid):
        nonlocal done
        async with sem:
            await one(args.host, args.port, path, uid, rec, args.timeout)
            done += 1
            if done % 500 == 0:
                print("      进度 %d/%d  已用 %.1fs" % (done, args.total,
                                                          time.perf_counter() - t0))

    print("=" * 78)
    print("直连基准：http://%s:%d%s" % (args.host, args.port, path))
    print("并发上限 %d，总请求 %d，userId %d ~ %d"
          % (args.concurrency, args.total, args.start_user,
             args.start_user + args.total - 1))
    print("=" * 78)

    tasks = [asyncio.create_task(worker(args.start_user + i))
             for i in range(args.total)]
    await asyncio.gather(*tasks)
    dur = time.perf_counter() - t0

    if not rec.lat:
        print("无样本")
        sys.exit(1)
    el = sorted(rec.lat)

    def pct(p):
        k = (len(el) - 1) * p
        lo, hi = int(k), min(int(k) + 1, len(el) - 1)
        return el[lo] + (el[hi] - el[lo]) * (k - lo)

    ok = rec.codes.get(200, 0)
    print()
    print("总请求        : %d" % len(el))
    print("成功(HTTP 200): %d  (%.2f%%)" % (ok, ok * 100.0 / len(el)))
    print("耗时          : %.1f s" % dur)
    print("吞吐          : %.1f req/s" % (len(el) / dur))
    print("RT 平均/P50/P90/P99/Max : %d / %d / %d / %d / %d ms"
          % (sum(el) / len(el), pct(.5), pct(.9), pct(.99), el[-1]))
    print("响应码分布    : %s" % dict(rec.codes.most_common()))
    if rec.errs:
        print("异常分布      : %s" % dict(rec.errs.most_common()))


if __name__ == "__main__":
    # 注意：Windows 上不要切到 SelectorEventLoop —— 它底层是 select()，
    # 单进程能持有的 fd 上限约 512，跑到 3000 并发会直接报错；
    # Python 3.8+ 默认的 ProactorEventLoop 才能撑住数千连接。
    asyncio.run(main())
