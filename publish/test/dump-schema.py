"""导出 nyw 库的表结构为 sql/schema.sql（一次性脚本，不入库）"""
import pymysql, os

HOST, PORT, USER, PWD, DB = "${MW_HOST}", 3306, "root", "${MW_PASSWORD}", "nyw"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "sql", "schema.sql")

conn = pymysql.connect(host=HOST, port=PORT, user=USER, password=PWD, database=DB, charset="utf8mb4")
with conn.cursor() as cur:
    cur.execute("SHOW TABLES")
    tables = [r[0] for r in cur.fetchall()]
    ddl = []
    for t in sorted(tables):
        cur.execute(f"SHOW CREATE TABLE `{t}`")
        ddl.append(cur.fetchone()[1])

order = ["address", "cart", "item", "order", "order_detail", "order_logistics",
         "pay_order", "user", "seckill_voucher", "seckill_order"]
tables_sorted = sorted(tables, key=lambda x: order.index(x) if x in order else 99)

# 重新按业务顺序取 DDL
ddl_map = {}
with conn.cursor() as cur:
    for t in tables_sorted:
        cur.execute(f"SHOW CREATE TABLE `{t}`")
        ddl_map[t] = cur.fetchone()[1]

header = f"""-- ============================================
-- seckill-trade-system 数据库结构
-- 库名：nyw    字符集：utf8mb4
-- 由 SHOW CREATE TABLE 导出，共 {len(tables_sorted)} 张表
-- ============================================

CREATE DATABASE IF NOT EXISTS `nyw` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `nyw`;

"""
body = ";\n\n".join(ddl_map[t] for t in tables_sorted) + ";\n"

seed = """

-- ============================================
-- 秒杀演示数据：一张库存 3000 的秒杀券（voucher_id=1, item_id=1）
-- 活动时间窗覆盖 2025-01-01 ~ 2030-12-31，可直接用于压测复现
-- ============================================
INSERT INTO `seckill_voucher` (voucher_id, item_id, stock, begin_time, end_time)
VALUES (1, 1, 3000, '2025-01-01 00:00:00', '2030-12-31 23:59:59');
"""

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write(header + body + seed)

print("tables:", tables_sorted)
print("written:", OUT, os.path.getsize(OUT), "bytes")
conn.close()
