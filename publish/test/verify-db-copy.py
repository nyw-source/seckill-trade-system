# -*- coding: utf-8 -*-
"""新旧库逐表行数对比验证"""
import pymysql

conn = pymysql.connect(host="${MW_HOST}", port=3306, user="root", password="${MW_PASSWORD}", autocommit=True)
cur = conn.cursor()
for old, new in [("hmall", "nyw"), ("hm-cart", "nyw-cart")]:
    cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s ORDER BY table_name", (old,))
    tables = [r[0] for r in cur.fetchall()]
    print("== %s -> %s（%d 张表）==" % (old, new, len(tables)))
    for t in tables:
        cur.execute("SELECT COUNT(*) FROM `%s`.`%s`" % (old, t))
        o = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM `%s`.`%s`" % (new, t))
        n = cur.fetchone()[0]
        mark = "OK" if o == n else "MISMATCH!"
        print("  %-28s 旧=%-6d 新=%-6d %s" % (t, o, n, mark))
conn.close()
