# JMeter 手动配置压测 + 打开数据库核对库存（从零手把手）

> 目标：不依赖现成 jmx 和 verify 脚本，**亲手在 JMeter GUI 里配出一个秒杀压测**，压完后**自己打开数据库看库存**得出零超卖结论。
> 所有路径、接口、表名、字段均已对照代码核实（SeckillController.java / init-seckill-test.sql / prepare-seckill-loadtest.py）。

---

## 第一部分：压测前的数据准备

### 1.1 确认测试券存在（打开数据库第一眼）

用任意 MySQL 客户端连接（IDEA Database 面板 / Navicat / DataGrip 均可）：

| 参数 | 值 |
|---|---|
| Host | `${MW_HOST}` |
| Port | `3306` |
| User | `root` |
| Password | `${MW_PASSWORD}` |
| Database | `nyw` |

连接后执行：

```sql
SELECT voucher_id, item_id, stock, begin_time, end_time
FROM seckill_voucher WHERE voucher_id = 1;
```

- **有记录** → 进入 1.2 复位
- **无记录** → 先建表插券（表结构在 `test/jmeter/init-seckill-test.sql`，可直接整个文件执行）

### 1.2 复位压测数据（每次压测前必须做，上一轮的残留会全部污染结果）

**MySQL 两张表**（在客户端里直接执行）：

```sql
-- 清空上一轮秒杀订单
DELETE FROM seckill_order WHERE voucher_id = 1;

-- 库存复位为 3000
UPDATE seckill_voucher SET stock = 3000 WHERE voucher_id = 1;
```

**Redis 三个键**（GuI 里 TestPlan 不用管，这是业务侧状态；用脚本最稳）：

```bash
python test/jmeter/prepare-seckill-loadtest.py --stock 3000 --voucher-id 1
```

它会清这 3 个 Redis 键并重设库存（手动用 redis-cli 等价操作也行）：

| Redis 键 | 含义 | 复位动作 |
|---|---|---|
| `nyw:seckill:stock:1` | 秒杀库存计数器 | DEL 后 SET 3000（必须先 DEL：预热代码用 setIfAbsent，不 DEL 会设不进去） |
| `nyw:seckill:order:set:1` | 已下单用户集合（一人一单依据） | DEL（不清空 = 全员被判重复下单，100% 失败） |
| `nyw:id:seckill:order` | 订单号自增序号 | DEL（重新从 1 计数） |

**压测前 4 个数字核对**（截图留底，压测后对比）：

```sql
-- MySQL
SELECT stock FROM seckill_voucher WHERE voucher_id = 1;   -- 期望 3000
SELECT COUNT(*) FROM seckill_order WHERE voucher_id = 1;  -- 期望 0
```
Redis 库存键 = 3000、order:set 成员数 = 0。

### 1.3 用户数据文件（CSV，**每次压测前必须重新签发 token**）

`test/jmeter/data/seckill-users.csv`，3000 行 + 表头，两列 `userId,token`。

> ⚠️ **token 有 6 小时有效期，隔天必过期**（过期症状：全部请求 401，且压测报告看起来"100% 失败"但实际是鉴权拦截不是业务失败）。压测前用 TokenGen 重新签发（命令已实测可用，Git-Bash 下执行）：
>
> ```bash
> cd test/jmeter/tools
> ${TOOLS}/javaSe/jdk17/bin/java -cp ".;${M2_REPO}/cn/hutool/hutool-all/5.8.11/hutool-all-5.8.11.jar" TokenGen ../../../gateway/src/main/resources/nyw.jks ${JKS_PASSWORD} nyw 100001 3000 ../data/seckill-users.csv
> ```
>
> 输出末尾的 `有效期至` 即本次压测的 token 保险期。

打开确认首行是 `userId,token`，每行一个**不同**用户 + 真实 JWT。

> 为什么必须一人一个身份：同一用户对同一张券最多成功 1 单（一人一单）。复用同一个身份压 3000 次，测出来的是"一人一单拦截"，不是"零超卖"。

---

## 第二部分：JMeter GUI 手动配置（从空计划开始）

### 2.0 启动 JMeter

```bash
${JMETER_HOME}/bin/jmeter.bat
```
中文界面：`Options → Choose Language → 简体中文`（若没自动中文化）。

**先保存**：`Ctrl+S` 存为 `test/jmeter/seckill-manual.jmx`（每配一步存一次，JMeter 不会自动保存）。

### 2.1 添加 HTTP 请求默认值（省得每处填 IP）

右键 **测试计划 → 添加 → 配置元件 → HTTP 请求默认值**
- 协议：`http`
- 服务器名称或 IP：`localhost`
- 端口：`8080`（走网关，别直连 8085——鉴权、限流都要经过网关才测得到）

### 2.2 添加 CSV 数据文件设置

右键 **测试计划 → 添加 → 配置元件 → CSV 数据文件设置**
- 文件名：`${HOME}\Desktop\seckill-trade-system\test\jmeter\data\seckill-users.csv`
- 文件编码：`UTF-8`
- 变量名称：`userId,token`
- 忽略首行：`True`
- **遇到 EOF 再次循环：`False`** ← 关键，用完即止绝不复用
- **遇到 EOF 终止线程：`True`**
- 文件读取方式：`所有线程共享同一份`（3000 线程各领一行不重复）

### 2.3 添加线程组

右键 **测试计划 → 添加 → 线程（用户）→ 线程组**
- 线程数：`3000`（首次先填 `100` 跑通链路，再调回 3000）
- Ramp-up（秒）：`30`（3000 线程在 30 秒内启动完毕）
- 循环次数：`1`

### 2.4 添加 HTTP 信息头管理器（挂在**线程组**下）

右键 **线程组 → 添加 → 配置元件 → HTTP 信息头管理器** → 添加一行：
- 名称：`Authorization`
- 值：`${token}`

> ⚠️ **裸 JWT，不要写 `Bearer ${token}`**。网关 AuthGlobalFilter 直接把整个值交给 hutool 解析，带前缀一律 401。

### 2.5 添加同步定时器（模拟同一瞬间抢）

右键 **线程组 → 添加 → 定时器 → 同步定时器（Synchronizing Timer）**
- 模拟用户组的数量：`3000`（与线程数一致；首次冒烟时填 `100`）
- 超时时间：`30000`（毫秒）

> 作用：3000 个线程全部就绪后**同一毫秒释放**。没有它，Ramp-up 30 秒意味着请求被摊薄，任意时刻在途请求只有百来个，压不出瞬时并发。这个定时器必须放在 HTTP 请求**之前**。

### 2.6 添加 HTTP 请求（核心）

右键 **线程组 → 添加 → 取样器 → HTTP 请求**
- 方法：`POST`
- 路径：`/seckill/1`（即 `/seckill/{voucherId}`，券 ID 写死 1 即可）
- Content-Type 不用填 body——接口无请求体，身份全靠 Authorization 头
- 其余保持默认（自动重定向可勾）

### 2.7 添加两个响应断言（区分"业务失败"和"系统失败"）

**断言 1**：右键 **HTTP 请求 → 添加 → 断言 → 响应断言**
- 测试字段：`响应代码`
- 模式匹配规则：`相等`
- 测试模式：`200`
- 自定义失败消息：`下单未返回 200`

**断言 2**：再加一个响应断言
- 测试字段：`响应文本`
- 模式匹配规则：`匹配（正则）`
- 测试模式：`\s*"?[0-9]+"?\s*`
- 自定义失败消息：`响应体不是订单号`

> 断言 2 说明：成功下单的响应体就是一个 19 位订单号。项目 `JsonConfig` 给 `Long` 注册了 `ToStringSerializer`，订单号以 JSON **字符串**形态返回（`"1234567890123456789"`）防止 JS 精度丢失，所以正则写成可选引号包数字。业务失败（如库存不足、重复下单）响应体不是纯数字 → 断言失败；这样聚合报告里 Error% 就能把两类失败分开。

### 2.8 添加监听器

右键 **线程组 → 添加 → 监听器**：
- **聚合报告（Aggregate Report）**：必加
- **察看结果树（View Results Tree）**：调试用，**正式压测前右键禁用**（每条响应驻留内存，3000 并发会和业务抢内存把 JMeter 自己压崩）
- **用表格察看结果（View Results in Table）**：可选，冒烟时直观

### 2.9 运行前检查单

```
□ 服务已启动：至少 gateway(8080) + seckill-service(8085)，且已注册 Nacos
□ MySQL 复位完成：stock=3000，seckill_order 无 voucher_id=1 记录
□ Redis 复位完成：stock 键=3000，order:set 空
□ 察看结果树已禁用（正式压测时）
□ Ctrl+S 保存了 jmx
```

点工具栏 **绿色 ▶** 开始。右侧数字跑完（Samples=3000 后停止不动）即结束，**点红色 ■ 停止按钮旁的扫帚图标清数据前，先看聚合报告**。

---

## 第三部分：压测中 / 压测后看库存（打开数据库）

### 3.1 压测进行中：实时看库存变化

MySQL 客户端里反复执行（或点客户端的"刷新"按钮）：

```sql
-- DB 库存（异步落库，压测中会逐步下降）
SELECT voucher_id, stock FROM seckill_voucher WHERE voucher_id = 1;

-- 已生成订单数（同步增长）
SELECT COUNT(*) FROM seckill_order WHERE voucher_id = 1;
```

**预期现象**：`seckill_order` 行数几乎瞬间冲到 3000；`seckill_voucher.stock` **滞后几秒到几十秒**才降到 0——因为下单走的是 Redis Lua 扣减 + RabbitMQ 异步落库，DB 库存由消费者逐条 `stock = stock - 1` 更新，压测刚结束的瞬间查 DB 看到还有余量**不是超卖**，等 MQ 消费完（秒级~分钟级）再下结论。

### 3.2 压测结束后的零超卖对账（全部在数据库客户端里完成）

**核对 1：库存一张不多一张不少**

```sql
SELECT voucher_id, stock FROM seckill_voucher WHERE voucher_id = 1;
```
3000 人抢 3000 张券 → 期望 **stock = 0**。若 > 0 说明有单没落库（查 MQ/消费者日志）；**永远不会 < 0，<0 就是超卖**。

**核对 2：订单数 = 请求成功数**

```sql
SELECT COUNT(*) FROM seckill_order WHERE voucher_id = 1;
```
期望 = 聚合报告里的成功样本数（= 3000，前提全部成功）。

**核对 3：一人一单（关键，防止同一人刷多单）**

```sql
-- 期望返回 0 行；返回了就是同一用户下出了多单
SELECT user_id, COUNT(*) AS cnt
FROM seckill_order
WHERE voucher_id = 1
GROUP BY user_id
HAVING cnt > 1;
```

**核对 4：订单去重用户数 = 订单数**

```sql
SELECT COUNT(*) AS 订单数, COUNT(DISTINCT user_id) AS 去重用户数
FROM seckill_order WHERE voucher_id = 1;
```
期望两个数字相等（3000 人每人 1 单）。

**核对 5（可选）：Redis 与 DB 一致**

```bash
python -c "
import redis
r=redis.Redis(host='${MW_HOST}',password='${MW_PASSWORD}',decode_responses=True)
print('Redis库存:', r.get('nyw:seckill:stock:1'))
print('已下单集合人数:', r.scard('nyw:seckill:order:set:1'))
"
```
期望 Redis 库存 0、集合 3000 人，与 MySQL 对上。

**核对 6（可选）：订单状态分布（15 分钟后看延迟关单）**

```sql
SELECT status, COUNT(*) FROM seckill_order WHERE voucher_id = 1 GROUP BY status;
-- 1=未支付 2=已支付 3=已关闭
-- 压测刚结束时全是 1（未支付）；约 15 分钟 TTL 到期后应全部变 3（已关闭），
-- 同时 seckill_voucher.stock 回补到 3000
```

### 3.3 结论判定表

| 检查项 | 零超卖合格线 |
|---|---|
| `seckill_voucher.stock` | = 0（绝不出现负数） |
| `seckill_order` 订单数 | = 3000（= 发放的库存数） |
| 一人多单查询 | 0 行 |
| 订单数 == 去重用户数 | 相等 |
| Redis 库存 | = 0，与 DB 一致 |
| 聚合报告 Error% | 非 0 时逐条看断言消息：`下单未返回 200` = 系统问题；`响应体不是订单号` = 业务拦截（如重复下单），属正常防护 |

---

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 全部 401 | Authorization 写成 `Bearer ${token}`，或 CSV 列顺序错 | 信息头只写 `${token}`；确认 CSV 变量名顺序 `userId,token` |
| 全部业务失败（断言 2 失败） | 忘了复位 Redis order:set，全员被判"重复下单" | 重跑 1.2 复位 |
| Samples 不足 3000 | CSV 被提前读完（Recycle 没关）或线程提前退出 | 检查 2.2 的 EOF 设置 |
| 压测中 DB 库存迟迟不到 0 | 异步落库正常滞后 | 等 MQ 消费完（看 15672 控制台队列清空）再核对 |
| JMeter 卡顿/OOM | GUI 下跑 3000 并发 + 察看结果树没禁用 | 禁用结果树；正式压测用 headless：`python test/jmeter/run-jmeter.py --jmx seckill-3000-concurrent.jmx --tag full3000` |
| 请求堆积越来越慢 | 同步定时器超时设置过小 | 超时 ≥ Ramp-up 时间（3000/30 秒场景给 30000ms） |
