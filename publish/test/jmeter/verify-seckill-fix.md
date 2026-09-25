# 秒杀改造验证清单（热点预热 + 死信库存回补）

> 配合本次改动使用，逐条打勾并截图/录屏留档。
> 代码位置：`seckill-service`、`nyw-common`。

---

## 0. 本次改了什么（代码 ↔ 说辞对齐表）

| 在简历/面试中会说的 | 代码位置 | 状态 |
| --- | --- | --- |
| CAS 关闭订单做幂等 | `SeckillOrderMapper#closeIfUnpaid`（`UPDATE ... WHERE id=? AND status=1`） | ✅ 已实现 |
| DB 库存回补 | `SeckillVoucherMapper#restoreStock`（`stock = stock + 1`） | ✅ 已实现 |
| 事务提交后再 increment Redis | `SeckillServiceImpl#closeTimeoutOrder` + `runAfterCommit` | ✅ 已实现 |
| srem 释放一人一单资格 | 同上（afterCommit 内 remove Set 成员） | ✅ 已实现 |
| Lua Set + DB 主键双保险幂等 | `seckill.lua` + `createSeckillOrder` 的 `selectById` | ✅ 原有能力，未改动 |
| 消费端跨 Bean 调用 @Transactional | `SeckillOrderListener` 注入 `SeckillServiceImpl` 代理 | ✅ 原有能力，未改动 |
| 活动开始前预热 | `POST /seckill/preheat/{voucherId}` | ✅ 新增 |
| **定时任务自动预热** | `SeckillServiceImpl#scheduledPreheat` + `@EnableScheduling` | ✅ 新增（可写进简历） |
| Redis 分布式锁防多实例重复预热 | 同上，Redisson 锁 `lock:mutex:seckill:preheat` | ✅ 新增 |
| 热点商品读缓存 | `GET /seckill/item/{itemId}` | ✅ 新增 |
| ~~AopContext 代理解决自调用~~ | 已删除（第 96 行死代码） | ✅ 已删除 |

新增端点：

```
POST /seckill/preheat/{voucherId}   预热单场（库存 + 关联商品），手动/脚本触发
POST /seckill/preheat              批量预热「正在进行 + 未来 30 分钟内开始」的场次
GET  /seckill/item/{itemId}        查询热点商品（优先读缓存，未命中回源并回填）
POST /seckill/init-stock/{voucherId}  老接口保留，内部已委托给 preheat
```

> ⚠️ `init-stock` 语义变化：库存 key 从 `SET`（覆盖）改成 `SETNX`（只写不覆盖）。
> 目的是**防止活动进行中重复预热把已扣减的库存重置回初始值**。
> 你原来的 `run-seckill-test.bat` 里 `redis-cli < init-seckill-redis.txt` 会先 `DEL` 再 `SET`，所以压测流程不受影响。

新增 Redis key：

```
nyw:seckill:item:{itemId}   热点商品缓存（JSON 字符串）
```

---

## 1. 验证 ①：同一商品查两次，第二次无 SQL

前置：`item-service`、`seckill-service` 已启动并注册到 Nacos。

```bash
# 1) 预热（会打一次 item-service，写缓存）
curl -X POST "http://<网关或seckill服务>:<port>/seckill/preheat/1"

# 2) 确认两个 key 都在（这是你要求的验证点）
redis-cli -h ${MW_HOST} -p 6379 GET  nyw:seckill:stock:1      # 期望 3000
redis-cli -h ${MW_HOST} -p 6379 GET  nyw:seckill:item:1       # 期望商品 JSON
redis-cli -h ${MW_HOST} -p 6379 TTL  nyw:seckill:item:1       # 期望 > 0

# 3) 连续查两次商品详情
curl "http://<网关或seckill服务>:<port>/seckill/item/1"
curl "http://<网关或seckill服务>:<port>/seckill/item/1"

# 4) 看 item-service 日志：两次请求只应有 1 条 SQL（第 2 次直接命中缓存）
```

反过来验证「未预热 → 首次回源、第二次命中」：

```bash
redis-cli -h ${MW_HOST} -p 6379 DEL nyw:seckill:item:1
curl .../seckill/item/1        # 回源 + 回填，item-service 有 SQL
curl .../seckill/item/1        # 命中缓存，item-service 无 SQL
```

**留档要点**：item-service 日志里两次请求的 SQL 条数对比。

---

## 2. 验证 ②：死信消息重复投递两次，库存只回补一次（面试必问）

### 2.1 前提问题已修复（2026-09-22）：独立延迟队列已落地

**修复前的问题**（保留记录）：`RabbitMqConfig` 把 `x-message-ttl(15min)` 配在了 `seckill.order.queue` 上，

```java
QueueBuilder.durable(SECKILL_ORDER_QUEUE)
        .deadLetterExchange(SECKILL_DEAD_EXCHANGE)
        .deadLetterRoutingKey(SECKILL_DEAD_ROUTING_KEY)
        .ttl(15 * 60 * 1000)          // ← 问题所在
        .build();
```

而 `SeckillOrderListener#handleSeckillOrder` 正在消费 **同一个队列**。默认 ack 模式下消息被消费后立即 ack 删除，
根本不会在队列里停留 15 分钟 —— 永远不会过期、永远不会进死信队列，`closeTimeoutOrder` 在生产上跑不到，
「15 分钟未支付自动关单」是失效的。

**已按方案 A 的「扇出版」实现**（比原方案更省：不用发两条消息）：

```java
// RabbitMqConfig：延迟队列故意没有消费者，只负责等 TTL
@Bean
public Queue seckillDelayQueue() {
    return QueueBuilder.durable(SECKILL_DELAY_QUEUE)      // seckill.order.delay.queue
            .ttl(ORDER_TIMEOUT_MILLIS)                    // 15 分钟
            .deadLetterExchange(SECKILL_DEAD_EXCHANGE)
            .deadLetterRoutingKey(SECKILL_DEAD_ROUTING_KEY)
            .build();
}

@Bean
public Binding seckillDelayBinding() {
    return BindingBuilder.bind(seckillDelayQueue())
            .to(seckillOrderExchange())
            .with(SECKILL_ORDER_ROUTING_KEY);             // ← 与订单队列同一个 routing key，天然扇出
}
```

`SeckillServiceImpl#seckillVoucher` 的发送代码**一行都不用改**：一次
`convertAndSend(SECKILL_ORDER_EXCHANGE, SECKILL_ORDER_ROUTING_KEY, orderDTO)`
会被交换机复制成两条 —— 一条进订单队列立即建单，一条进延迟队列等 15 分钟。

同时：`seckillOrderQueue()` 去掉了 `.ttl(...)`；`application.yaml` 新增
`listener.simple.default-requeue-rejected: false`，建单失败的消息拒绝后走死信交换机，
而不是被无限重投刷日志（拒绝后进入 `seckill.dead.queue`，此时订单已回滚，关单 CAS 0 行幂等跳过）。

> ✅ 本机已实测（`python test/verify-infra.py --only mq`）：
> C1 一次发送后两个队列各 1 条、C2 TTL 到期转死信队列 1 条、C3 消息体完整可取 —— 全通过。
> 注：测试时把 TTL 临时压成 5 秒，真实配置是 15 分钟。

### 2.2 验证步骤（不管拓扑怎么修，幂等本身都能这样验）

**方法一：SQL 层直接证明 CAS 幂等（不需要重启任何服务）**

```sql
-- 造一条「未支付」订单（orderId 换成你实际生成的）
UPDATE seckill_order SET status = 1 WHERE id = <orderId>;
SELECT stock FROM seckill_voucher WHERE voucher_id = 1;   -- 记下 stock0

-- 第一次投递：代码走完整流程
UPDATE seckill_order SET status = 3 WHERE id = <orderId> AND status = 1;  -- 影响 1 行
UPDATE seckill_voucher SET stock = stock + 1 WHERE voucher_id = 1;

-- 第二次投递（同一条消息）：CAS 影响 0 行 → 代码直接 return，下面这句不会执行
UPDATE seckill_order SET status = 3 WHERE id = <orderId> AND status = 1;  -- 影响 0 行

SELECT stock FROM seckill_voucher WHERE voucher_id = 1;   -- 必须 = stock0 + 1，只加了一次
```

**方法二：走真实 MQ（需要临时加 3 行调试代码）**

在 `SeckillController` 临时加：

```java
@PostMapping("/test/dead-letter/{orderId}")
public String testDeadLetter(@PathVariable("orderId") Long orderId) {
    SeckillOrderDTO dto = new SeckillOrderDTO(orderId, 1L, 1L, 1L);
    rabbitTemplate.convertAndSend(SECKILL_DEAD_EXCHANGE, SECKILL_DEAD_ROUTING_KEY, dto);
    rabbitTemplate.convertAndSend(SECKILL_DEAD_EXCHANGE, SECKILL_DEAD_ROUTING_KEY, dto);
    return "sent twice";
}
```

> 必须是**同一条 DTO 发两次**，这才等价于「死信重复投递」。
> 注意项目里没有配置 `Jackson2JsonMessageConverter`，用的是默认 `SimpleMessageConverter`
> （Java 序列化），所以不能拿管理界面手填 JSON body，只能从应用内发。

执行后看日志顺序，只允许出现一次回补：

```
第一次：超时订单已关闭，orderId=xxx  →  Redis 库存与一人一单资格已回补
第二次：订单不存在或已处理，跳过关闭（幂等），orderId=xxx   ← 没有回补日志
```

同时：

```bash
redis-cli ... GET   nyw:seckill:stock:1        # 只 +1
redis-cli ... SISMEMBER nyw:seckill:order:set:1 <userId>   # 期望 0
mysql> SELECT status, stock FROM seckill_order o, seckill_voucher v
       WHERE o.id=<orderId> AND v.voucher_id=1;  -- status=3，stock 只 +1
```

**留档要点**：`sent twice` → 两条日志（一条回补、一条跳过）→ 库存只 +1 的截图。

---

## 3. 验证 ③：超时用户能重新下单

```bash
# 用户 1 秒杀成功
curl -X POST ".../seckill/1" -H "Authorization: Bearer <user1-token>"
redis-cli ... SISMEMBER nyw:seckill:order:set:1 1     # 期望 1

# 触发关单（用上面 2.2 方法二）
redis-cli ... SISMEMBER nyw:seckill:order:set:1 1     # 期望 0  ← 关键
redis-cli ... GET nyw:seckill:stock:1                 # 库存已 +1

# 用户 1 再次秒杀 → 应成功（不再报「您已参与过该秒杀」）
curl -X POST ".../seckill/1" -H "Authorization: Bearer <user1-token>"
```

**留档要点**：srem 前后 `SISMEMBER` 从 1 → 0，且用户能再次下单成功。

---

## 4. 验证 ④：定时预热

1. 造一条「未来 5 分钟内开始」的券：

```sql
INSERT INTO seckill_voucher (voucher_id, item_id, stock, begin_time, end_time, create_time, update_time)
VALUES (2, 1, 100, DATE_ADD(NOW(), INTERVAL 5 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), NOW(), NOW());
```

2. 清缓存，然后什么都不做，最多等 1 分钟：

```bash
redis-cli ... DEL nyw:seckill:stock:2
redis-cli ... DEL nyw:seckill:item:1
```

3. 观察：

```bash
redis-cli ... GET nyw:seckill:stock:2      # 100
redis-cli ... GET nyw:seckill:item:1       # 商品 JSON
```

日志中应出现：

```
定时预热任务完成，本次预热 1 个场次
```

4. 多实例验证（可选加分项）：同时起两个 seckill-service，日志里只有一个实例打印
   「定时预热任务完成」，另一个只打印「其他实例正在执行预热任务，本次跳过」。

> cron 默认 `0 * * * * ?`（每分钟一次）。如需调整，在 `application.yaml` 加：
> ```yaml
> nyw:
>   seckill:
>     preheat-cron: "0 */5 * * * ?"
> ```

---

## 5. 尚未验证 / 需要你确认的点

> **2026-09-22 17:0x 更新**：中间件 VM 已确认是 `${MW_HOST}`（Redis/MySQL/Nacos/RabbitMQ 全在这台），
> 全项目地址已统一过去，并且在本机跑通了 `python test/verify-infra.py`（12 项全过，见 `test/环境与验证报告.md`）。
> 也就是说下面第 1 条的「验证 ①②」已经在**基础设施层**真机验证完毕，只剩「启动整个 Spring 应用后跑接口」这一层没做。

1. **服务级验证还差一步**：本机跑不了「启服务→调接口」的链路，原因是 ——
   Nacos 8848 通、但客户端 2.x 必需的 **9848/9849 端口不通**（VM 防火墙/容器映射问题），
   服务注册会直接失败。修好 9848 后这条完整链路（`POST /seckill/preheat/1`、`GET /seckill/item/1`）就能验了。
2. **死信链路不会自动触发**：✅ 已修复，见 2.1（独立延迟队列 + routing key 扇出），本机真机验证通过。
3. `SeckillApplication` 上的 `@EnableAspectJAutoProxy(exposeProxy = true)`：✅ 已删除。
4. `createSeckillOrder` 里 DB 库存扣减失败：✅ 已改为抛 `BizIllegalException` 回滚订单。
   ⚠️ 遗留风险：此时 Redis 的库存已经扣了、该用户也进了「一人一单」集合，而订单被回滚，
   属于「Redis 说抢到了、DB 扣不动」的不一致。当前只打 error 日志，建议后续加一个
   「Redis 库存与 DB 库存定时对账」任务兜底。
5. **秒杀券表原先在库里不存在**：✅ 已按 `init-seckill-test.sql` 建好（含 voucher_id=1、stock=3000）。
6. **分布式 ID 生成器原先 100% 抛异常**：✅ 已修复（`时间戳<<32 | Redis 序列号`），详见 `test/环境与验证报告.md` 第 4 节。
