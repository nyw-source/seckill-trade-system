# IDEA 手动启动服务 + JMeter 压测完整操作手册

> 适用环境：Windows + IDEA + JDK 17 + Git-Bash，项目根 `${HOME}\Desktop\seckill-trade-system`
> 中间件全部在 VM `${MW_HOST}`（MySQL 3306 / Redis 6379 / RabbitMQ 5672 / Nacos 8848+9848+9849）

---

## 第一部分：IDEA 手动启动各服务

### 步骤 0：启动前置环境（每次都先做）

1. **VM 开机**，确认 VMware 虚机 `Red Hat Enterprise Linux 9` 已启动。
2. **Nacos 必须先起**（它是硬依赖，没起服务会启动即自杀）。
   在 VM 终端执行：
   ```bash
   docker start nacos
   ```
3. **本机验证 4 个中间件端口全通**（Git-Bash 或 PowerShell）：
   ```bash
   python -c "
   import socket
   for p in (3306, 6379, 5672, 8848, 9848, 9849):
       s=socket.socket(); s.settimeout(2)
       try: s.connect(('${MW_HOST}',p)); print(p,'OK')
       except Exception as e: print(p,'FAIL')
       finally: s.close()
   "
   ```
   6 个全 OK 才继续。9848/9849 是 Nacos 2.x gRPC 端口，缺一个服务注册就会失败。
4. **释放内存**（IDEA 本身占 ~3GB，6 个服务共需 1.5~2.5GB）：
   ```bash
   python test/jmeter/free-memory.py --apply
   ```
   白名单脚本，只关 Edge/豆包/B站/JMeter GUI，不会动 IDEA 和 VMware。

### 步骤 1：打开项目并检查构建配置

1. IDEA 打开 `${HOME}\Desktop\seckill-trade-system`。
2. `File → Project Structure → Project`：SDK 必须是 **17**，Language level 11 以上。
3. 右侧 Maven 面板，点 **刷新（Reload All Maven Projects）**，等索引转完。
4. ⚠️ 如果**改过任何代码或 pom**：先在 Maven 面板根节点 `seckill-trade-system` 上执行
   `clean install`（Lifecycle 里双击 clean，再双击 install）。
   只重启不改代码可跳过。

### 步骤 2：逐个创建启动配置

菜单 `Run → Edit Configurations → + → Application`，每个服务建一条：

| 配置名（建议） | 模块（Use classpath of module） | Main class | 端口 |
|---|---|---|---|
| item-service | item-service | `com.nyw.item.ItemApplication` | 8081 |
| cart-service | cart-service | `com.nyw.cart.CartApplication` | 8082 |
| user-service | user-service | `com.nyw.user.UserApplication` | 8083 |
| order-service | order-service | `com.nyw.order.OrderApplication` | 8084 |
| seckill-service | seckill-service | `com.nyw.seckill.SeckillApplication` | 8085 |
| gateway | gateway | `com.nyw.gateway.GatewayApplication` | 8080 |

每条配置统一设置：
- **JRE**：选 17（`Use module JDK` 即可）
- **Modify options → Add VM options**，填：
  ```
  -Xmx384m -Xss512k
  ```
  （内存紧张时必须加；机器空闲可去掉）
- **Active profiles**：留空（配置在 `application.yaml` 默认段）
- ⚠️ **不要勾选 `Enable debug`** 之外的特殊选项；不要加 `--spring.cloud.nacos.discovery.enabled=false`（那只是验启动链路的临时绕开，加了这个网关路由不通）

> 顺手清理：`Edit Configurations` 里如果还有红色失效的 `HMallApplication`（指向已删的 hm-service），选中点 `-` 删掉。

### 步骤 3：启动顺序

按这个顺序点各配置旁的绿色 ▶：

**item → user → cart → order → seckill → gateway（最后）**

原因：gateway 的 `lb://` 路由按 Nacos 服务列表转发，下游全注册完再起网关，路由一次通。

### 步骤 4：确认每个服务启动成功

每个服务的控制台出现这三样才算成功：

1. `nacos registry, ... register finished`（成功注册 Nacos）
2. `Tomcat started on port(s): 808x (http)`
3. `Started XxxApplication in X.XXX seconds`

全局确认：浏览器开 **http://${MW_HOST}:8848/nacos**（nacos/nacos 登录），`服务管理 → 服务列表`，应看到 6 个服务：gateway、item-service、cart-service、user-service、order-service、seckill-service。

冒烟验证（浏览器或 curl，走网关）：
```
http://localhost:8080/items/page?pageNo=1&pageSize=1     → 200，返回商品 JSON
http://localhost:8080/carts/list                          → 401（正常，未登录被拦截）
```

### 常见启动失败对照表（都是真踩过的坑）

| 报错关键字 | 原因 | 解决 |
|---|---|---|
| `Connection refused .../9848` → `Failed to start bean 'webServerStartStop'` | Nacos 没起 / VM 没放行 gRPC 端口 | `docker start nacos`；容器必须映射 8848+9848+9849 |
| `UnrecoverableKeyException: BadPaddingException` | nyw.jks 与 yaml 里 alias/密码不一致 | gateway/user 已配对；出现于你手动换了 jks 文件时 |
| `Unable to find a single main class [com.hmall..., com.nyw...]` | target 残留旧编译产物 | Maven 面板执行 clean 后再启动 |
| `NoClassDefFoundError: com/alibaba/csp/sentinel/...` | 改了 pom 没重新构建 | clean install 后重启 |
| 定时任务启动即抛 cron 错误 | cron 少了一段 | 本项目 cron 一律 6 段：`0 */2 * * * ?` |
| 改过 `order-timeout-minutes` 后 RabbitMQ 报 `PRECONDITION_FAILED` | 队列 TTL 参数不可变 | 控制台 http://${MW_HOST}:15672 删掉 `seckill.order.delay.queue` 再重启 |

---

## 第二部分：JMeter 压测秒杀接口

### 你已有的东西（不用新建）

| 文件 | 用途 |
|---|---|
| `test/jmeter/seckill-3000-concurrent.jmx` | 3000 并发正式压测脚本 |
| `test/jmeter/seckill-smoke-100.jmx` | 100 并发冒烟脚本 |
| `test/jmeter/data/seckill-users.csv` | 3000 个不同 userId + 真实 JWT（每线程一行，绝不复用） |
| `test/jmeter/prepare-seckill-loadtest.py` | 压测前复位数据（Redis+MySQL） |
| `test/jmeter/run-jmeter.py` | headless 运行器（自动调 JVM 参数） |
| `test/jmeter/verify-seckill-loadtest.py` | 压测后自动对账（零超卖验证） |
| `test/jmeter/check-seckill-state.py` | 随时查当前库存/订单状态 |
| `test/jmeter/analyze-seckill-log.py` | 服务日志中文关键词分析（日志是 GB18030，别用 grep） |
| `test/jmeter/watch-delayed-close.py` | 实时观察延迟关单 |
| `${JMETER_HOME}` | JMeter 本体 |

脚本设计要点（`seckill-3000-concurrent.jmx` 内置，面试会问）：
- **每线程从 CSV 取不同 userId + JWT**——同一身份复用测出来的是「一人一单」不是「零超卖」
- **Authorization 头放裸 JWT**，不带 `Bearer ` 前缀（网关直接 hutool 解析，带前缀 401）
- **同步定时器**让 3000 线程攒齐同一瞬间释放，否则 30 秒 ramp 下实际在途请求只有个位数
- **断言** HTTP 200 + 响应体是纯数字订单号（业务失败与系统失败可区分）

### 方式 A：一键压测（推荐，复现性最好）

压测只需要 **gateway(8080) + seckill-service(8085)** 在跑，但全 6 个起着也没关系。

双击或 cmd 执行：
```cmd
test\jmeter\run-seckill-test.bat
```
它自动做三步：复位数据 → headless 跑 JMeter → 输出报告位置。

或手动分步（Git-Bash，便于看每步输出）：

```bash
# 1. 复位压测数据：券库存=3000，清空 Redis 已下单集合 + MySQL 订单
python test/jmeter/prepare-seckill-loadtest.py --stock 3000 --voucher-id 1

# 2. headless 跑 3000 并发（结果落 test/jmeter/out/full3000/）
python test/jmeter/run-jmeter.py --jmx seckill-3000-concurrent.jmx --tag full3000

# 3. 自动对账验证零超卖
python test/jmeter/verify-seckill-loadtest.py --voucher-id 1 --expect 3000
```

先跑冒烟验证链路（100 并发）：
```bash
python test/jmeter/prepare-seckill-loadtest.py --stock 100 --voucher-id 1
python test/jmeter/run-jmeter.py --jmx seckill-smoke-100.jmx --tag smoke100
python test/jmeter/verify-seckill-loadtest.py --voucher-id 1 --expect 100
```

### 方式 B：JMeter GUI 手动操作（学习/演示用）

⚠️ GUI 模式只用于调试和演示，正式 3000 并发务必用方式 A——GUI 渲染本身吃内存，会把客户端先压垮。

1. **启动 GUI**：
   ```bash
   ${JMETER_HOME}/bin/jmeter.bat -t test/jmeter/seckill-3000-concurrent.jmx
   ```
2. **界面里能看到脚本结构**（从上到下）：
   - **CSV 数据文件设置**：`seckill-users.csv`，变量 `userId,token`，`Recycle on EOF = False`、`Stop thread on EOF = True`（3000 行用完即止，绝不循环复用）
   - **HTTP信息头管理器**：`Authorization: ${token}`（裸 JWT）
   - **线程组**：`Number of Threads = 3000`、`Ramp-up = 30`、`Loop Count = 1`
   - **同步定时器（Synchronizing Timer）**：`Number of Simultaneous Users = 3000`——3000 线程攒齐才放行，模拟同一秒抢券
   - **HTTP请求**：`POST /seckill/${VOUCHER_ID}`（即 `/seckill/1`，走 `BASE_URL:PORT` = `localhost:8080` 网关；SeckillController 的 `@RequestMapping("/seckill")` + `@PostMapping("/{voucherId}")`）
   - **响应断言**：HTTP 200 + 响应体匹配正则纯数字订单号
3. **添加监听器看数据**（测试计划右键 → 添加 → 监听器）：
   - **聚合报告（Aggregate Report）**：必加，核心指标
   - **查看结果树（View Results Tree）**：只调试用，正式压测前禁用（每条请求都存内存）
4. **压测前必须复位数据**（GUI 不复位的话 Redis 里还有上一轮的已下单集合，3000 人会全部被「一人一单」拦住，测出来 100% 失败）：
   ```bash
   python test/jmeter/prepare-seckill-loadtest.py --stock 3000 --voucher-id 1
   ```
5. **点工具栏绿色 ▶ 开始**，聚合报告数字实时跳动，跑完 CSV 3000 行自动结束。

### 怎么看数据

#### 1. JMeter 聚合报告各列含义

| 列 | 含义 | 合格线 |
|---|---|---|
| Samples | 总请求数，应=3000 | 少于 3000 说明线程提前退出 |
| Average / Median / 90% Line | 平均 / 中位 / 90 分位响应时间(ms) | Median 和 90% Line 比平均更可信 |
| Min / Max | 最快/最慢请求 | Max 出现几秒属正常（并发瞬峰） |
| **Error %** | 失败率 | **业务正确时 ≈ 0%**；非 0 先区分是断言失败（业务失败）还是连接失败（系统崩了） |
| Throughput | 每秒处理请求数(QPS) | 3000 单应在数秒内打完 |

**零超卖 ≠ Error%=0 这一层**：`Error%` 只证明 HTTP 层和断言。真正的零超卖靠第 3 步对账。

#### 2. 结果文件

headless 模式产物在 `test/jmeter/out/<tag>/`：
- `*.jtl` —— 每条请求一行（时间戳、耗时、状态、断言结果），Excel 可开
- `*.log` —— JMeter 自身运行日志

用 Python 快速统计 jtl：
```bash
python -c "
import csv
rows=list(csv.reader(open('test/jmeter/out/full3000/<jtl文件名>', encoding='utf-8'), '\t'))
hdr=rows[0]; data=rows[1:]
ok=[r for r in data if r[3]=='true']
err=[r for r in data if r[3]!='true']
ts=[int(r[1]) for r in ok]
print('total:',len(data),'success:',len(ok),'fail:',len(err))
if ts: print('span:',(max(ts)-min(ts))/1000,'s')
"
```

#### 3. 零超卖对账（核心验证，必做）

```bash
python test/jmeter/verify-seckill-loadtest.py --voucher-id 1 --expect 3000
```
它自动核对四件事，全部 PASS 才算零超卖闭环：
1. **MySQL 订单数 == 3000**（每请求恰好一单）
2. **MySQL 券库存 == 0**（3000 张一张不多一张不少）
3. **Redis 库存 == 0** 且与 MySQL 一致（缓存与库不漂移）
4. **一人一单**：无 userId 下多于 1 单

手工复核（留证据截图用）：
```bash
# MySQL：订单数 + 一人一单（表名以实际为准：seckill_order / seckill_voucher）
python -c "
import pymysql
c=pymysql.connect(host='${MW_HOST}',user='root',password='${MW_PASSWORD}',db='nyw')
cur=c.cursor()
cur.execute(\"select count(*), count(distinct user_id) from seckill_order where voucher_id=1\")
print('订单数/去重用户数:', cur.fetchone())
cur.execute(\"select stock from seckill_voucher where voucher_id=1\")
print('DB剩余库存:', cur.fetchone()[0])
"
# Redis：剩余库存（应为 0）
python -c "
import redis
r=redis.Redis(host='${MW_HOST}',password='${MW_PASSWORD}',decode_responses=True)
print('Redis剩余库存:', r.get('nyw:seckill:stock:1'))
"
```
> Redis key 前缀以 `nyw-common` 的 `RedisKeyConstants` 为准：`nyw:seckill:stock:{voucherId}`；可用 `KEYS nyw:seckill:*` 确认实际 key。

#### 4. 服务端日志分析（延迟关单/异常）

⚠️ 服务日志是 **GB18030 编码**，Git-Bash 的 `grep` 匹配不到中文且不报错——一律用现成脚本：

```bash
# 日志关键词统计（成功/失败/关单/回补等）
python test/jmeter/analyze-seckill-log.py

# 实时观察延迟关单（压测后 15 分钟 TTL 到期时看库存回补）
python test/jmeter/watch-delayed-close.py
```

延迟关单验证流程：压测结束后启动 `watch-delayed-close.py`，约 15 分钟后应看到：
- RabbitMQ `seckill.order.delay.queue` TTL 到期 → 消息路由到 `seckill.order.close.queue` → 关单消费者把 `status 1→3`、Redis 库存 INCR 回补
- 控制台 http://${MW_HOST}:15672 （guest/guest）→ Queues 页看各队列消息进出

#### 5. 每次压测的标准动作清单

```
□ 中间件 6 端口全通（含 9848）
□ 6 服务注册 Nacos（或至少 gateway + seckill-service）
□ free-memory.py --apply 释放内存
□ prepare-seckill-loadtest.py 复位数据（忘记=上一轮残留干扰）
□ headless 跑压测（run-jmeter.py）
□ verify-seckill-loadtest.py 对账（零超卖结论的唯一依据）
□ analyze-seckill-log.py 看服务端日志
□ 压测完成后停服务、关 VM（可选）
```
