# -*- coding: utf-8 -*-
"""
秒杀压测 · JMeter headless 运行器

为什么用 Python 包一层而不是直接写 .bat / .sh：
  * Git-Bash 里调用 jmeter.bat 时，`/n`、`/t` 这类参数会被当成路径改写（MSYS 路径转换），
    出现「无效参数」或找不到文件；用 subprocess 直接传数组参数可以完全绕开。
  * 3000 线程必须调 JVM 参数：JMeter 默认 HEAP 是 -Xms1g -Xmx1g，
    3000 个线程加每个样本的缓冲会直接 OOM，压测结果又会变成「客户端先崩」。
    jmeter.bat 支持用 HEAP / JVM_ARGS 环境变量覆盖，这里固化下来。
  * -Xss 调小到 256k：每个线程栈默认 1M，3000 线程光栈就 3G 虚拟内存。

用法：
    python run-jmeter.py --jmx seckill-3000-concurrent.jmx --tag full3000
    python run-jmeter.py --jmx seckill-smoke-100.jmx --tag smoke100
"""
import argparse
import os
import re
import subprocess
import sys
import time

DEFAULT_JMETER_HOME = r"${JMETER_HOME}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jmeter-home", default=os.environ.get("JMETER_HOME", DEFAULT_JMETER_HOME))
    ap.add_argument("--jmx", required=True, help="jmx 文件名（相对 test/jmeter）或绝对路径")
    ap.add_argument("--tag", default="run", help="产物前缀，落盘到 test/jmeter/out/<tag>/")
    ap.add_argument("--heap", default="-Xms512m -Xmx2g -XX:MaxMetaspaceSize=256m")
    ap.add_argument("--xss", default="256k")
    ap.add_argument("-J", dest="props", action="append", default=[],
                    help="透传给 JMeter 的属性，形如 host=localhost")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))  # test/jmeter -> test -> 仓库根
    jmx = args.jmx if os.path.isabs(args.jmx) else os.path.join(here, args.jmx)

    if not os.path.isfile(jmx):
        print("找不到 jmx：%s" % jmx)
        sys.exit(2)

    out_dir = os.path.join(here, "out", args.tag)
    os.makedirs(out_dir, exist_ok=True)
    jtl = os.path.join(out_dir, "result.jtl")
    report = os.path.join(out_dir, "html")
    jmeter_log = os.path.join(out_dir, "jmeter.log")

    for p in (jtl, jmeter_log):
        if os.path.exists(p):
            os.remove(p)
    if os.path.isdir(report):
        import shutil
        shutil.rmtree(report)

    bat = os.path.join(args.jmeter_home, "bin", "jmeter.bat")
    if not os.path.isfile(bat):
        print("找不到 jmeter.bat：%s" % bat)
        sys.exit(2)

    cmd = [bat, "-n", "-t", jmx, "-l", jtl, "-e", "-o", report, "-j", jmeter_log]
    for kv in args.props:
        cmd += ["-J" + kv]

    env = dict(os.environ)
    env["HEAP"] = args.heap
    env["JVM_ARGS"] = "-Xss" + args.xss
    # 关掉 JMeter 自身的 UTF-8 探测噪声，日志更容易读
    env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "")

    print("=" * 78)
    print("jmx      : %s" % jmx)
    print("jtl      : %s" % jtl)
    print("html     : %s" % report)
    print("HEAP     : %s" % args.heap)
    print("JVM_ARGS : -Xss%s" % args.xss)
    if args.props:
        print("props    : %s" % ", ".join(args.props))
    print("=" * 78)

    t0 = time.time()
    proc = subprocess.run(cmd, cwd=repo, env=env, capture_output=True)
    dur = time.time() - t0

    out = proc.stdout.decode("gbk", "ignore") + proc.stderr.decode("gbk", "ignore")
    # JMeter 的汇总表直接回显出来，省得再翻文件
    if "summary =" in out:
        tail = out[out.index("summary ="):]
        print(tail.strip()[:4000])
    else:
        print(out.strip()[-4000:])

    print("=" * 78)
    print("退出码 %d，耗时 %.1fs" % (proc.returncode, dur))

    # OOM / 线程创建失败要显式点出来，否则又会被误读成「服务端有问题」
    for pat in ("OutOfMemoryError", "unable to create", "ThreadDeath", "java.lang.OutOfMemory"):
        if re.search(pat, out, re.I):
            print("!! 客户端疑似资源不足，命中关键字：%s  —— 先看 %s" % (pat, jmeter_log))
        elif os.path.isfile(jmeter_log):
            with open(jmeter_log, "r", encoding="utf-8", errors="ignore") as f:
                if re.search(pat, f.read(), re.I):
                    print("!! jmeter.log 命中关键字：%s" % pat)

    if os.path.isfile(jtl):
        print("样本文件已生成：%s（%.1f KB）"
              % (jtl, os.path.getsize(jtl) / 1024.0))
    else:
        print("!! 未生成 result.jtl，压测未真正跑起来")

    print("HTML 报告：%s" % os.path.join(report, "index.html"))


if __name__ == "__main__":
    main()
