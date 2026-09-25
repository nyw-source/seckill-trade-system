# -*- coding: utf-8 -*-
"""
压测前释放内存：只关「可随时重启的桌面应用」，白名单保护 IDEA / VMware / WorkBuddy / 微信 / explorer。

用法：
    python free-memory.py            # 预演，只列出将被关闭的进程
    python free-memory.py --apply    # 真正执行
"""
import csv
import ctypes
import os
import subprocess
import sys
import time

# 允许关闭的进程（都是可随时重开的桌面应用）
KILL_LIST = {
    "javaw.exe": "JMeter GUI（随后会用 headless 模式重新拉起）",
    "Doubao.exe": "豆包桌面端",
    "哔哩哔哩.exe": "B站桌面端",
    "msedge.exe": "Microsoft Edge",
}

# 保护名单：显式列出，防止误杀
PROTECT = {
    "idea64.exe", "vmware-vmx.exe", "WorkBuddy.exe", "Weixin.exe",
    "WeChatAppEx.exe", "explorer.exe", "dwm.exe", "python.exe",
    "node.exe", "java.exe", "taskkill.exe", "conhost.exe",
}


class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ("dwLength", ctypes.c_ulong),
        ("dwMemoryLoad", ctypes.c_ulong),
        ("ullTotalPhys", ctypes.c_ulonglong),
        ("ullAvailPhys", ctypes.c_ulonglong),
        ("ullTotalPageFile", ctypes.c_ulonglong),
        ("ullAvailPageFile", ctypes.c_ulonglong),
        ("ullTotalVirtual", ctypes.c_ulonglong),
        ("ullAvailVirtual", ctypes.c_ulonglong),
        ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
    ]


def free_gb():
    m = MEMORYSTATUSEX()
    m.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
    return m.ullAvailPhys / 2 ** 30, m.dwMemoryLoad


def list_processes():
    """返回 [(pid, name, mem_mb)]，tasklist 输出为 GBK。"""
    raw = subprocess.run(["tasklist", "/FO", "CSV", "/NH"],
                         capture_output=True).stdout.decode("gbk", "ignore")
    out = []
    for r in csv.reader(raw.splitlines()):
        if len(r) < 5:
            continue
        try:
            kb = int(r[4].replace(",", "").replace(" K", "").replace("K", "").strip())
        except ValueError:
            continue
        out.append((int(r[1]), r[0], kb / 1024))
    return out


def main():
    apply_kill = "--apply" in sys.argv
    before, load = free_gb()
    print("当前可用内存：%.2f GB（占用 %d%%）" % (before, load))
    print("=" * 78)

    procs = list_processes()
    targets, saved_kb = [], 0
    for pid, name, mb in procs:
        if name in PROTECT:
            continue
        if name in KILL_LIST:
            targets.append((pid, name, mb))
            saved_kb += mb

    if not targets:
        print("没有匹配到需要关闭的进程。")
        return

    print("将关闭以下 %d 个进程，预计释放 %.2f GB：" % (len(targets), saved_kb / 1024))
    for pid, name, mb in sorted(targets, key=lambda x: -x[2]):
        print("  %8.1f MB  pid=%-7d %-16s %s" % (mb, pid, name, KILL_LIST[name]))

    if not apply_kill:
        print("\n[预演模式] 未实际关闭。加 --apply 执行。")
        return

    print("\n开始关闭……")
    for pid, name, _ in targets:
        r = subprocess.run(["taskkill", "/PID", str(pid), "/F"],
                           capture_output=True)
        ok = "成功" if r.returncode == 0 else "失败(%d)" % r.returncode
        print("  pid=%-7d %-16s %s" % (pid, name, ok))
        time.sleep(0.3)

    time.sleep(2)
    after, load2 = free_gb()
    print("=" * 78)
    print("释放后可用内存：%.2f GB（占用 %d%%），净增 %.2f GB"
          % (after, load2, after - before))


if __name__ == "__main__":
    main()
