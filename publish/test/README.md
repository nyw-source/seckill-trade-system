# 验证资产（脱敏副本）

本目录是 `test/` 下验证脚本与报告的**脱敏副本**，用于随仓库公开。
原始文件保留在本地 `test/`（含本地路径与联调凭据，已通过 `.gitignore` 排除，不入库）。

## 目录内容

| 路径 | 说明 |
|---|---|
| `verify-infra.py` | **不启动服务**，直接打真实 Redis / MySQL / RabbitMQ，12 项断言：Lua 脚本并发不超卖、CAS 关单幂等、MQ 拓扑正确性 |
| `e2e/verify-seckill-e2e.py` | 启动服务后的 24 项端到端断言：下单 → 延迟关单 → 重复投递幂等 → 扫表兜底 → 日志守恒（约 90 秒） |
| `e2e/verify-item-cache.py` | 商品缓存 11 项断言（通过统计日志中 SQL 条数验证缓存命中） |
| `e2e/verify-gateway-auth.py` | 网关越权验证：伪造 `X-User-Id` 是否被摘除 |
| `jmeter/` | 压测工具链：JMX 脚本、3000 账号批量签发 token、结果解析与零超卖验收、延迟关单观测 |
| `jmeter/analyze-seckill-log.py` | 关单链路日志守恒校验（**按 GB18030 显式解码**，含 `.gz`） |
| `秒杀压测诊断与结果报告.md` | 压测报告：4 组场景数据 + 零超卖验收 + 延迟关单闭环 |
| `e2e/秒杀超时关单A+B实施与验证报告.md` | 双保险关单方案的实施与真机验证 |
| `启动问题诊断与修复报告.md` | 6 个阻塞启动的 P0 问题定位与修复过程 |
| `环境与验证报告.md` | 中间件地址核定 + 真机 12 项验证 + 4 个 blocker |

## 脱敏说明

以下内容已替换为占位符，**直接运行脚本前需要自行替换为真实值**：

| 占位符 | 含义 |
|---|---|
| `${MW_HOST}` | 中间件主机（MySQL / Redis / RabbitMQ / Nacos 同机） |
| `${MW_HOST_OLD}` | 已失效的历史中间件地址 |
| `${MW_PASSWORD}` | 中间件密码（MySQL root / Redis） |
| `${JKS_PASSWORD}` | JWT 密钥库口令 |
| `${HOME}` | 本机用户目录 |
| `${TOOLS}` | 本机工具安装根目录 |
| `${JMETER_HOME}` / `${JAVA_HOME}` / `${M2_REPO}` | JMeter / JDK / Maven 本地仓库路径 |

## 不包含的文件

| 文件 | 原因 |
|---|---|
| `jmeter/data/seckill-users.csv` | 含 3000 个**真实可用**的 JWT（由仓库内密钥库签发），公开等于泄露可复用身份凭证 |
| `jmeter/tools/TokenGen.class` | 编译产物，应由 `.java` 现场编译 |
| `jmeter/out/`、`_shots/` | JMeter HTML 报告与截图等中间产物 |

`seckill-users.csv` 可用仓库内工具现场生成：

```bash
javac -encoding UTF-8 -cp <hutool-all.jar> -d test/jmeter/tools test/jmeter/tools/TokenGen.java
java -cp "test/jmeter/tools;<hutool-all.jar>" TokenGen \
     gateway/src/main/resources/nyw.jks ${JKS_PASSWORD} nyw 100001 3000 test/jmeter/data/seckill-users.csv
```

## 复现压测

```bash
# 0) 前置：MySQL / Redis / RabbitMQ / Nacos 就绪，服务已注册到 Nacos
#    改过 order-timeout-minutes 的话，需先在 15672 删除 seckill.order.delay.queue
#    （队列级 TTL 是队列属性，参数变更会报 PRECONDITION_FAILED）

python test/jmeter/prepare-seckill-loadtest.py --stock 3000   # 复位 Redis 与 MySQL
python test/jmeter/run-jmeter.py --jmx seckill-smoke-100.jmx --tag smoke100
python test/jmeter/run-jmeter.py --jmx seckill-3000-concurrent.jmx --tag full3000
python test/jmeter/verify-seckill-loadtest.py \
       --jtl test/jmeter/out/full3000/result.jtl --expect 3000 --stock 3000

python test/jmeter/watch-delayed-close.py --minutes 20         # 观测延迟关单
python test/jmeter/analyze-seckill-log.py                      # 日志守恒校验
python test/jmeter/check-seckill-state.py                      # 三方口径快照
```

> ⚠️ 日志文件是 **GB18030** 编码。Git-Bash 的 `grep` / `rg` 在 `LANG=C.UTF-8` 下匹配不到任何中文且**静默返回 0**，
> 会得出「关单从未发生」这类完全错误的结论 —— 中文统计一律走 `analyze-seckill-log.py`。
