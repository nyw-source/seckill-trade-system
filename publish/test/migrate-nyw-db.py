# -*- coding: utf-8 -*-
"""
犇牛商城（nyw）库迁移 + Redis 预热脚本
=====================================
背景：代码已将库名从 hmall/hm-cart 改为 nyw/nyw-cart、Redis key 前缀从 hmall: 改为 nyw:，
本脚本把 ${MW_HOST} 上的旧库数据复制到新库，并按 init-seckill-redis.txt 预热秒杀数据。

安全设计：
  - 只创建/复制，绝不删除旧库（hmall、hm-cart 保留，确认无误后自行 DROP）
  - 幂等：新库已存在的表自动跳过
  - 连接参数可用命令行覆盖

用法（在本机 Windows 上）：
    python test\\migrate-nyw-db.py
    python test\\migrate-nyw-db.py --mysql-host ${MW_HOST} --redis-host ${MW_HOST}
依赖：pip install pymysql redis
"""
import argparse
import sys

import pymysql
import redis

DEFAULT_HOST = "${MW_HOST}"
MYSQL_PORT = 3306
REDIS_PORT = 6379
PASSWORD = "${MW_PASSWORD}"

# 旧库 -> 新库 映射
DB_PAIRS = [
    ("hmall", "nyw"),
    ("hm-cart", "nyw-cart"),
]


def q(ident):
    """MySQL 标识符加反引号（库名可能含连字符）"""
    return "`" + ident.replace("`", "``") + "`"


def migrate_mysql(host, user, password):
    conn = pymysql.connect(host=host, port=MYSQL_PORT, user=user,
                           password=password, autocommit=True)
    cur = conn.cursor()
    print("=" * 60)
    print("步骤 1/2：MySQL 库复制（%s:3306，root）" % host)
    print("=" * 60)
    for old_db, new_db in DB_PAIRS:
        cur.execute("SHOW DATABASES LIKE %s", (old_db,))
        old_exists = cur.fetchone() is not None
        if not old_exists:
            print("[跳过] 旧库 %s 不存在，无需迁移" % old_db)
            continue
        # 统计旧库表
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", (old_db,))
        old_tables = cur.fetchone()[0]
        # 创建新库（幂等）
        cur.execute("CREATE DATABASE IF NOT EXISTS %s DEFAULT CHARACTER SET utf8mb4" % q(new_db))
        print("\n[库] %s -> %s（旧库 %d 张表）" % (old_db, new_db, old_tables))
        # 列出旧库表
        cur.execute(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=%s ORDER BY table_name",
            (old_db,))
        tables = [r[0] for r in cur.fetchall()]
        copied, skipped = 0, 0
        for t in tables:
            cur.execute(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s AND table_name=%s",
                (new_db, t))
            if cur.fetchone()[0] > 0:
                skipped += 1
                print("  [跳过] %s.%s 已存在" % (new_db, t))
                continue
            cur.execute("CREATE TABLE %s.%s LIKE %s.%s" % (q(new_db), q(t), q(old_db), q(t)))
            cur.execute("INSERT INTO %s.%s SELECT * FROM %s.%s" % (q(new_db), q(t), q(old_db), q(t)))
            copied += 1
        # 验证新库表数
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", (new_db,))
        new_tables = cur.fetchone()[0]
        print("  [结果] 复制 %d 张，跳过 %d 张；新库 %s 现有 %d 张表" % (copied, skipped, new_db, new_tables))
        if copied and new_tables == old_tables:
            print("  [OK] 表数与旧库一致")
        elif copied == 0 and new_tables == old_tables:
            print("  [OK] 新库已完整，无需复制")
        else:
            print("  [警告] 表数不一致：旧 %d / 新 %d，请人工核对" % (old_tables, new_tables))
    cur.close()
    conn.close()


def preheat_redis(host, password):
    print("\n" + "=" * 60)
    print("步骤 2/2：Redis 秒杀数据预热（%s:6379）" % host)
    print("=" * 60)
    r = redis.Redis(host=host, port=REDIS_PORT, password=password, decode_responses=True)
    try:
        r.ping()
    except Exception as e:
        print("[错误] Redis 连接失败：%s" % e)
        sys.exit(1)
    print("[OK] Redis PING 通过")
    # 等价 init-seckill-redis.txt
    r.delete("nyw:seckill:stock:1", "nyw:seckill:order:set:1", "nyw:id:seckill:order")
    r.set("nyw:seckill:stock:1", 3000)
    stock = r.get("nyw:seckill:stock:1")
    members = r.scard("nyw:seckill:order:set:1")
    print("[OK] nyw:seckill:stock:1 = %s（预期 3000）" % stock)
    print("[OK] nyw:seckill:order:set:1 成员数 = %d（预期 0）" % members)
    if stock == "3000" and members == 0:
        print("[OK] 预热完成，可启动 seckill-service")
    else:
        print("[警告] 预热结果异常，请检查")


def main():
    ap = argparse.ArgumentParser(description="犇牛商城 nyw 库迁移 + Redis 预热")
    ap.add_argument("--mysql-host", default=DEFAULT_HOST)
    ap.add_argument("--redis-host", default=DEFAULT_HOST)
    ap.add_argument("--mysql-user", default="root")
    args = ap.parse_args()

    print("目标环境：MySQL %s:3306 / Redis %s:6379（root / ${MW_PASSWORD}）" % (args.mysql_host, args.redis_host))
    migrate_mysql(args.mysql_host, args.mysql_user, PASSWORD)
    preheat_redis(args.redis_host, PASSWORD)
    print("\n全部完成。旧库 hmall / hm-cart 仍保留，确认无误后可手动删除：")
    print("  DROP DATABASE hmall; DROP DATABASE `hm-cart`;")


if __name__ == "__main__":
    main()
