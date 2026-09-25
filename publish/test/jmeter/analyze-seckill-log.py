# -*- coding: utf-8 -*-
"""
秒杀服务日志分析（GB18030 安全）

为什么需要这个脚本：
  1. 服务端 JVM 的 file.encoding 是 GBK，Logback 写出的 spring.log 是 GB18030 编码。
     Git-Bash 的 grep / ripgrep 在 LANG=C.UTF-8 下**匹配不到中文**，
     连"文件里明明存在"的串都返回 0 —— 会静默给出假阴性结论。
  2. 轮转文件 spring.log.*.gz 是 gzip 压缩的，直接按文本读只能拿到乱码。

本脚本同时解决这两点：显式按 GB18030 解码 + 自动解压 .gz。

用法：
    python analyze-seckill-log.py
    python analyze-seckill-log.py --log-dir ../../seckill-service/logs/seckill-service
"""
import argparse
import glob
import gzip
import os
import re
from collections import Counter

LOG_DIR = r"${HOME}\Desktop\seckill-trade-system\seckill-service\logs\seckill-service"

# 语义分组：建单链路 / 关单链路 / 兜底 / 异常
GROUPS = {
    "建单": ["收到秒杀订单创建消息", "秒杀订单创建成功", "秒杀订单创建失败"],
    "关单": ["收到超时订单关单消息", "超时订单已关闭", "跳过关闭"],
    "回补": ["资格已回补"],
    "扫表兜底": ["扫表发现", "扫表兜底关单完成", "扫表兜底任务"],
    "死信兜底": ["收到建单失败死信消息", "死信消息处理失败"],
}


def read_log(path):
    """显式按 GB18030 解码，.gz 自动解压"""
    if path.endswith(".gz"):
        with gzip.open(path, "rb") as f:
            raw = f.read()
    else:
        with open(path, "rb") as f:
            raw = f.read()
    # 服务端 file.encoding=GBK；个别 ASCII 行用 utf-8 也兼容，统一走 gb18030
    return raw.decode("gb18030", errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log-dir", default=LOG_DIR)
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.log_dir, "spring.log*")))
    if not files:
        print("未找到日志文件：", args.log_dir)
        return

    print("=" * 68)
    print("秒杀服务日志分析（GB18030 解码，含 .gz 轮转文件）")
    print("=" * 68)
    for f in files:
        print(f"  {os.path.basename(f):<40}{os.path.getsize(f):>12,} bytes")

    per_file = {}
    for f in files:
        text = read_log(f)
        per_file[os.path.basename(f)] = text

    print("\n[关键词计数]")
    print(f"  {'分组':<10}{'关键词':<26}{'当前 spring.log':>16}{'累计':>10}")
    for group, keys in GROUPS.items():
        for i, k in enumerate(keys):
            cur = sum(t.count(k) for n, t in per_file.items() if n == "spring.log")
            tot = sum(t.count(k) for t in per_file.values())
            label = group if i == 0 else ""
            print(f"  {label:<10}{k:<26}{cur:>16}{tot:>10}")

    # ---- 关单端到端闭环判定 ----
    full = "\n".join(per_file.values())
    recv = full.count("收到超时订单关单消息")
    closed = full.count("超时订单已关闭")
    skipped = full.count("跳过关闭")
    restored = full.count("资格已回补")
    print("\n[关单闭环]")
    print(f"  收到关单消息 = {recv}")
    print(f"  真实关闭     = {closed}   （CAS 命中 status=1）")
    print(f"  幂等跳过     = {skipped}   （订单已删除/已支付/已关闭）")
    print(f"  Redis 回补   = {restored}")
    if recv and recv == skipped + closed:
        print("  ✅ 消息数守恒：收到 = 真实关闭 + 幂等跳过")
    elif recv:
        print(f"  ⚠ 消息数与处理数不守恒（差 {recv - skipped - closed}），可能有在途或异常")
    else:
        print("  – 尚无关单消息（TTL 未到期）")

    # ---- 时间轴：关单消息的时间分布 ----
    pat = re.compile(r"^(\d{2}:\d{2}:\d{2}):\d{3}\s+\w+\s+.*?(收到超时订单关单消息|超时订单已关闭|跳过关闭)")
    buckets = Counter()
    for line in full.splitlines():
        m = pat.match(line)
        if m:
            buckets[(m.group(1)[:5], m.group(2))] += 1
    if buckets:
        print("\n[关单时间轴（分钟）]")
        for (minute, what), n in sorted(buckets.items()):
            print(f"  {minute}  {what:<22}{n}")

    # ---- 异常 ----
    errs = re.findall(r"^.*?(?:ERROR|WARN).*?$", full, re.M)
    uniq = Counter()
    for e in errs:
        # 归一化：去掉时间戳与具体数值，便于聚合
        key = re.sub(r"^[\d:]+", "", e)
        key = re.sub(r"\d+", "N", key)[:110]
        uniq[key] += 1
    print("\n[异常 Top 10]")
    for k, n in uniq.most_common(10):
        print(f"  {n:>6}  {k.strip()}")
    print("=" * 68)


if __name__ == "__main__":
    main()
