package com.nyw.seckill.task;

import com.nyw.common.cache.RedisKeyConstants;
import com.nyw.common.utils.CollUtils;
import com.nyw.seckill.domain.dto.SeckillOrderDTO;
import com.nyw.seckill.domain.po.SeckillOrder;
import com.nyw.seckill.service.ISeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 超时订单「扫表兜底」关单任务
 *
 * <p>与延迟队列构成双保险：</p>
 * <ul>
 *   <li>延迟队列负责<b>实时</b>关单（正常路径，延迟 15 分钟精确定时）；</li>
 *   <li>本任务负责捞<b>漏网</b>的单子：延迟消息发送失败、消息在 broker 侧丢失、
 *       消费者重试 3 次仍失败被丢弃、服务停机期间到期的消息，以及历史上遗留的脏数据。</li>
 * </ul>
 *
 * <p><b>为什么单独一个 Bean 而不是写在 SeckillServiceImpl 里：</b>
 * 如果在同一个类里调用 closeTimeoutOrder，属于自调用（this 调用），
 * Spring AOP 无法拦截，@Transactional 会被静默忽略，
 * 结果就是 CAS 关单与库存回补不在同一事务、"提交后再回补 Redis" 的回调也不会注册。
 * 放到独立 Bean 里通过注入的代理对象调用，事务才真正生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillTimeoutScanTask {

    /** 订单超时默认分钟数：与 RabbitMqConfig#DEFAULT_ORDER_TIMEOUT_MINUTES 保持一致 */
    private static final int DEFAULT_TIMEOUT_MINUTES = 15;

    private final ISeckillService seckillService;
    private final RedissonClient redissonClient;

    /** 单轮最多处理笔数：控制单次任务对数据库和 Redis 的压力，剩余的下轮继续 */
    private static final int SCAN_BATCH_SIZE = 100;

    /** 扫表任务分布式锁 key：多实例部署时同一时刻只让一个实例扫 */
    private static final String SCAN_LOCK_KEY =
            RedisKeyConstants.LOCK_MUTEX + RedisKeyConstants.SEPARATOR + "seckill:close-scan";

    /** 锁持有时长（秒） */
    private static final long SCAN_LOCK_LEASE_SECONDS = 30L;

    /** 订单超时分钟数：与 RabbitMqConfig 里延迟队列的 TTL 保持同一个配置项 */
    @Value("${nyw.seckill.order-timeout-minutes:" + DEFAULT_TIMEOUT_MINUTES + "}")
    private int orderTimeoutMinutes;

    /**
     * 定时扫描超时未支付订单并逐笔关闭
     * <p>每 2 分钟一次；关单逻辑 CAS 幂等，重复执行、与延迟队列撞车都不会把库存补多。
     * cron 可通过 nyw.seckill.close-scan-cron 覆盖。</p>
     */
    // 注意：Spring 的 @Scheduled cron 必须是 6 段（秒 分 时 日 月 周），
    // 这里的默认值原本写成 5 段 "0 */2 * * ?"，启动时直接抛
    // IllegalStateException: Cron expression must consist of 6 fields，
    // 且因为 yaml 里配了同名的 5 段值，服务在「不覆盖 cron」的默认配置下根本起不来。
    @Scheduled(cron = "${nyw.seckill.close-scan-cron:0 */2 * * * ?}")
    public void scanTimeoutOrders() {
        RLock lock = redissonClient.getLock(SCAN_LOCK_KEY);
        try {
            if (!lock.tryLock(0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                log.debug("其他实例正在执行超时订单扫描，本次跳过");
                return;
            }
            LocalDateTime deadline = LocalDateTime.now().minusMinutes(orderTimeoutMinutes);
            List<SeckillOrder> orders = seckillService.findTimeoutOrders(deadline, SCAN_BATCH_SIZE);
            if (CollUtils.isEmpty(orders)) {
                return;
            }
            log.warn("扫表发现 {} 笔超时未支付订单，开始兜底关单（deadline={}）", orders.size(), deadline);
            int closed = 0;
            for (SeckillOrder order : orders) {
                try {
                    // 复用同一套 CAS 关单逻辑：已支付 / 已关闭的订单影响行数为 0，自动跳过
                    seckillService.closeTimeoutOrder(new SeckillOrderDTO(
                            order.getId(), order.getVoucherId(), order.getUserId(), order.getItemId()));
                    closed++;
                } catch (Exception e) {
                    // 单笔失败不影响其余订单，下一轮扫描会重试
                    log.error("兜底关闭超时订单失败，orderId={}", order.getId(), e);
                }
            }
            log.info("扫表兜底关单完成，本轮处理 {} 笔", closed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("扫表兜底任务获取锁被中断");
        } catch (Exception e) {
            // 定时任务必须吞掉异常，否则一次失败会影响后续调度
            log.error("扫表兜底任务执行失败", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
