package com.nyw.seckill.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.nyw.api.client.ItemClient;
import com.nyw.api.dto.ItemDTO;
import com.nyw.common.cache.RedisKeyConstants;
import com.nyw.common.exception.BadRequestException;
import com.nyw.common.exception.BizIllegalException;
import com.nyw.common.utils.CollUtils;
import com.nyw.common.utils.DistributedIdGenerator;
import com.nyw.common.utils.UserContext;
import com.nyw.seckill.domain.dto.SeckillOrderDTO;
import com.nyw.seckill.domain.po.SeckillOrder;
import com.nyw.seckill.domain.po.SeckillVoucher;
import com.nyw.seckill.mapper.SeckillOrderMapper;
import com.nyw.seckill.mapper.SeckillVoucherMapper;
import com.nyw.seckill.service.ISeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.nyw.seckill.config.RabbitMqConfig.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements ISeckillService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final RabbitTemplate rabbitTemplate;
    private final SeckillVoucherMapper voucherMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final DistributedIdGenerator distributedIdGenerator;
    private final ItemClient itemClient;

    /** 预热提前量（分钟）：活动开始前 30 分钟完成预热 */
    private static final int PREHEAT_AHEAD_MINUTES = 30;

    /** 热点商品缓存基础 TTL（秒），30 分钟 */
    private static final long ITEM_CACHE_BASE_TTL = 30 * 60L;

    /** 预热任务分布式锁持有时长（秒） */
    private static final long PREHEAT_LOCK_LEASE_SECONDS = 60L;

    /** 预热任务分布式锁 key */
    private static final String PREHEAT_LOCK_KEY =
            RedisKeyConstants.LOCK_MUTEX + RedisKeyConstants.SEPARATOR + "seckill:preheat";

    private DefaultRedisScript<Long> seckillScript;

    @PostConstruct
    public void init() {
        seckillScript = new DefaultRedisScript<>();
        seckillScript.setLocation(new ClassPathResource("lua/seckill.lua"));
        seckillScript.setResultType(Long.class);
    }

    @Override
    public Long seckillVoucher(Long voucherId) {
        // 1. 查询秒杀券信息
        SeckillVoucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw new BadRequestException("秒杀券不存在");
        }
        // 2. 校验秒杀时间
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(voucher.getBeginTime())) {
            throw new BadRequestException("秒杀尚未开始");
        }
        if (now.isAfter(voucher.getEndTime())) {
            throw new BadRequestException("秒杀已结束");
        }

        Long userId = UserContext.getUser();
        if (userId == null) {
            // 网关未透传 X-User-Id（或直接访问服务）时给出明确提示，避免 NPE
            throw new BadRequestException("未登录或登录已过期");
        }
        // 3. 生成秒杀订单 ID
        Long orderId = distributedIdGenerator.nextSeckillOrderId();

        // 4. 执行 Lua 脚本（原子校验库存 + 一人一单 + 扣减库存）
        String stockKey = RedisKeyConstants.seckillStockKey(voucherId);
        String orderSetKey = RedisKeyConstants.seckillOrderSetKey(voucherId);
        Long result = stringRedisTemplate.execute(
                seckillScript,
                Arrays.asList(stockKey, orderSetKey),
                userId.toString()
        );

        // 5. 判断结果
        if (result == null) {
            throw new BadRequestException("秒杀系统繁忙，请稍后再试");
        }
        int r = result.intValue();
        if (r == 1) {
            throw new BadRequestException("库存不足");
        }
        if (r == 2) {
            throw new BadRequestException("您已参与过该秒杀");
        }

        // 6. 使用 Redisson 分布式锁保护发消息动作，秒杀成功后异步创建订单
        String lockKey = RedisKeyConstants.LOCK_MUTEX + RedisKeyConstants.SEPARATOR + "seckill:order:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取分布式锁失败，orderId={}", orderId);
                throw new BadRequestException("系统繁忙，请稍后再试");
            }
            SeckillOrderDTO orderDTO = new SeckillOrderDTO(orderId, voucherId, userId, voucher.getItemId());
            // ① 建单消息：进入 seckill.order.queue，被消费者立即取出建单
            rabbitTemplate.convertAndSend(SECKILL_ORDER_EXCHANGE, SECKILL_ORDER_ROUTING_KEY, orderDTO);
            // ② 延迟关单消息：进入无消费者的 seckill.order.delay.queue，躺满 TTL 后经死信交换机转入关单队列
            //    这条发送失败不影响下单（订单照样能创建），漏掉的单子由定时扫表任务兜底关闭
            try {
                rabbitTemplate.convertAndSend(SECKILL_ORDER_DELAY_EXCHANGE, SECKILL_ORDER_DELAY_ROUTING_KEY, orderDTO);
            } catch (Exception e) {
                log.error("延迟关单消息发送失败，该订单将由扫表兜底任务关闭，orderId={}", orderId, e);
            }
            log.info("秒杀成功，订单已提交，orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("系统繁忙，请稍后再试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return orderId;
    }

    /**
     * 初始化秒杀库存到 Redis（管理接口，保留兼容）
     * <p>已升级为「预热」：库存 key 与关联热点商品缓存一起写入，实现委托给 {@link #preheat(Long)}</p>
     */
    @Override
    public void initStock(Long voucherId) {
        preheat(voucherId);
    }

    // ==================== 热点数据预热 ====================

    @Override
    public void preheat(Long voucherId) {
        SeckillVoucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw new BadRequestException("秒杀券不存在");
        }
        preheatStock(voucher);
        preheatItems(Collections.singletonList(voucher));
    }

    @Override
    public int preheatUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusMinutes(PREHEAT_AHEAD_MINUTES);
        // 正在进行中 + 未来 30 分钟内即将开始的场次
        List<SeckillVoucher> vouchers = voucherMapper.selectPreheatCandidates(now, deadline);
        if (CollUtils.isEmpty(vouchers)) {
            return 0;
        }
        vouchers.forEach(this::preheatStock);
        preheatItems(vouchers);
        return vouchers.size();
    }

    /**
     * 预热库存 key：用 setIfAbsent 而不是 set，
     * 避免活动进行中重复预热把已经扣减过的库存重置回数据库初始值
     */
    private void preheatStock(SeckillVoucher voucher) {
        String stockKey = RedisKeyConstants.seckillStockKey(voucher.getVoucherId());
        Boolean created = stringRedisTemplate.opsForValue()
                .setIfAbsent(stockKey, String.valueOf(voucher.getStock()));
        if (Boolean.TRUE.equals(created)) {
            log.info("秒杀库存预热完成，voucherId={}, stock={}", voucher.getVoucherId(), voucher.getStock());
        } else {
            log.info("秒杀库存 key 已存在，跳过覆盖（防止重置活动中的库存），voucherId={}", voucher.getVoucherId());
        }
    }

    /**
     * 批量预热关联商品：调用商品服务批量查询后写入缓存
     * <p>商品预热失败不影响库存预热，避免因下游抖动导致秒杀券不可用</p>
     */
    private void preheatItems(List<SeckillVoucher> vouchers) {
        if (CollUtils.isEmpty(vouchers)) {
            return;
        }
        List<Long> itemIds = vouchers.stream()
                .map(SeckillVoucher::getItemId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtils.isEmpty(itemIds)) {
            return;
        }
        try {
            // 一次 Feign 调用批量查询，避免循环单查造成 N 次网络往返
            List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
            if (CollUtils.isEmpty(items)) {
                log.warn("热点商品预热失败：商品服务未返回数据，itemIds={}", itemIds);
                return;
            }
            long ttl = itemCacheTtl(vouchers);
            for (ItemDTO item : items) {
                stringRedisTemplate.opsForValue().set(
                        RedisKeyConstants.seckillItemKey(item.getId()),
                        JSONUtil.toJsonStr(item),
                        ttl, TimeUnit.SECONDS);
            }
            log.info("热点商品预热完成，itemIds={}, ttl={}s", itemIds, ttl);
        } catch (Exception e) {
            log.error("热点商品预热异常，itemIds={}，库存已预热，商品缓存将在首次访问时回源", itemIds, e);
        }
    }

    /**
     * 商品缓存 TTL：覆盖到活动结束后 10 分钟，并加随机抖动防雪崩
     */
    private long itemCacheTtl(List<SeckillVoucher> vouchers) {
        LocalDateTime now = LocalDateTime.now();
        long maxSeconds = vouchers.stream()
                .map(SeckillVoucher::getEndTime)
                .filter(Objects::nonNull)
                .mapToLong(end -> Duration.between(now, end).getSeconds() + 600)
                .max()
                .orElse(ITEM_CACHE_BASE_TTL);
        long ttl = Math.max(ITEM_CACHE_BASE_TTL, maxSeconds);
        return ttl + ThreadLocalRandom.current().nextLong(0, 300);
    }

    @Override
    public ItemDTO querySeckillItem(Long itemId) {
        String key = RedisKeyConstants.seckillItemKey(itemId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, ItemDTO.class);
        }
        // 缓存未命中（未预热 / 已过期）→ 回源商品服务并回填缓存，避免并发时反复打库
        List<ItemDTO> items = itemClient.queryItemByIds(Collections.singletonList(itemId));
        if (CollUtils.isEmpty(items)) {
            return null;
        }
        ItemDTO item = items.get(0);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(item),
                ITEM_CACHE_BASE_TTL, TimeUnit.SECONDS);
        log.info("热点商品缓存回源并回填，itemId={}", itemId);
        return item;
    }

    /**
     * 定时预热：默认每分钟执行一次，预热「正在进行 + 未来 30 分钟内即将开始」的场次
     * <p>多实例部署时用 Redisson 锁保证同一时刻只有一个实例真正执行，
     * 避免 N 个实例重复预热、重复打商品服务</p>
     */
    @Scheduled(cron = "${nyw.seckill.preheat-cron:0 * * * * ?}")
    public void scheduledPreheat() {
        RLock lock = redissonClient.getLock(PREHEAT_LOCK_KEY);
        try {
            if (!lock.tryLock(0, PREHEAT_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                log.debug("其他实例正在执行预热任务，本次跳过");
                return;
            }
            int count = preheatUpcoming();
            if (count > 0) {
                log.info("定时预热任务完成，本次预热 {} 个场次", count);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("定时预热任务获取锁被中断");
        } catch (Exception e) {
            // 定时任务必须吞掉异常，否则一次失败会影响后续调度
            log.error("定时预热任务执行失败", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 异步建单 / 超时关单 ====================

    /**
     * 异步创建秒杀订单（MQ 消费者调用）
     * <p>消费者 Bean 通过注入的代理对象调用本方法，跨 Bean 调用保证 @Transactional 生效</p>
     */
    @Override
    @Transactional
    public void createSeckillOrder(SeckillOrderDTO orderDTO) {
        // 幂等性校验：Lua 的 Set 已挡住重复请求，DB 主键做最后一道去重
        SeckillOrder existOrder = seckillOrderMapper.selectById(orderDTO.getOrderId());
        if (existOrder != null) {
            log.warn("订单已存在，跳过创建，orderId={}", orderDTO.getOrderId());
            return;
        }

        // 保存订单
        SeckillOrder order = new SeckillOrder();
        order.setId(orderDTO.getOrderId());
        order.setUserId(orderDTO.getUserId());
        order.setVoucherId(orderDTO.getVoucherId());
        order.setItemId(orderDTO.getItemId());
        order.setStatus(1); // 1=未支付
        order.setCreateTime(LocalDateTime.now());
        seckillOrderMapper.insert(order);

        // 扣减数据库库存：原子 SQL 由数据库保证并发安全，stock > 0 兜底防超卖
        int rows = voucherMapper.deductStock(orderDTO.getVoucherId());
        if (rows == 0) {
            // DB 兜底库存已耗尽：必须回滚，否则会落一条「有订单、没库存」的超卖单
            // 抛运行时异常 → @Transactional 回滚订单 insert
            // 配合 listener.simple.default-requeue-rejected=false，消息不会被无限重投，
            // 拒绝后经死信交换机进 seckill.dead.queue；此时订单已回滚，关单逻辑 CAS 0 行幂等跳过
            log.error("数据库库存扣减失败（库存不足或秒杀券不存在），回滚订单，orderId={}, voucherId={}",
                    orderDTO.getOrderId(), orderDTO.getVoucherId());
            throw new BizIllegalException("数据库库存不足，回滚秒杀订单：" + orderDTO.getOrderId());
        }
        log.info("秒杀订单入库成功，orderId={}, userId={}, voucherId={}",
                orderDTO.getOrderId(), orderDTO.getUserId(), orderDTO.getVoucherId());
    }

    /**
     * 查询超时未支付订单（扫表兜底任务调用）
     * <p>走 (status, create_time) 联合索引，LIMIT 控制单轮批量，避免全表扫描</p>
     */
    @Override
    public List<SeckillOrder> findTimeoutOrders(LocalDateTime deadline, int limit) {
        return seckillOrderMapper.selectTimeoutOrders(deadline, limit);
    }

    /**
     * 关闭超时未支付订单（关单队列消费者 / 扫表兜底任务调用）
     * <p>三步都必须是幂等的，因为死信消息可能被重复投递：</p>
     * <ol>
     *   <li>CAS 关单：只有「未支付」能被关闭，影响行数 0 直接返回</li>
     *   <li>DB 库存原子回补</li>
     *   <li>Redis 库存与一人一单资格在事务提交后回补</li>
     * </ol>
     */
    @Override
    @Transactional
    public void closeTimeoutOrder(SeckillOrderDTO orderDTO) {
        Long orderId = orderDTO.getOrderId();
        Long voucherId = orderDTO.getVoucherId();
        String userId = String.valueOf(orderDTO.getUserId());

        // 1. CAS 关闭订单：只有 status=1（未支付）才会被关闭
        //    影响行数为 0 说明订单不存在 / 已支付 / 已被关闭，直接返回
        //    这一步是幂等的关键，防止死信重复投递导致库存被重复回补
        int rows = seckillOrderMapper.closeIfUnpaid(orderId);
        if (rows == 0) {
            log.info("订单不存在或已处理，跳过关闭（幂等），orderId={}", orderId);
            return;
        }

        // 2. DB 库存回补：原子 stock = stock + 1
        voucherMapper.restoreStock(voucherId);

        // 3. Redis 库存回补 + 释放一人一单资格，放在事务提交之后执行
        //    否则事务回滚时会出现「DB 没回补成功，Redis 却已经多补」的数据不一致
        runAfterCommit(() -> {
            stringRedisTemplate.opsForValue()
                    .increment(RedisKeyConstants.seckillStockKey(voucherId));
            stringRedisTemplate.opsForSet()
                    .remove(RedisKeyConstants.seckillOrderSetKey(voucherId), userId);
            log.info("Redis 库存与一人一单资格已回补，orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId);
        });

        log.info("超时订单已关闭，orderId={}, voucherId={}", orderId, voucherId);
    }

    /**
     * 注册事务提交后回调；无事务时立即执行
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 事务已提交，抛异常也无法回滚，只记录日志供人工核对
                    log.error("事务提交后回补 Redis 失败，需人工核对，action 已执行失败", e);
                }
            }
        });
    }
}
