package com.nyw.seckill.mq;

import com.nyw.seckill.domain.dto.SeckillOrderDTO;
import com.nyw.seckill.service.ISeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.nyw.seckill.config.RabbitMqConfig.*;

/**
 * 秒杀订单消息监听器
 * <ul>
 *   <li>监听 seckill.order.queue：异步创建订单</li>
 *   <li>监听 seckill.order.close.queue：关闭超时未支付订单（延迟队列 TTL 到期后转过来）</li>
 *   <li>监听 seckill.dead.queue：建单失败消息的兜底处理</li>
 * </ul>
 * <p>三条链路最终都调用 {@link ISeckillService} 的公开方法（跨 Bean 调用），
 * 保证 @Transactional 通过 Spring 代理生效 —— 若在同一个类里自调用，事务注解会被静默忽略。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderListener {

    private final ISeckillService seckillService;

    /**
     * ① 监听建单队列，异步写入数据库
     */
    @RabbitListener(queues = SECKILL_ORDER_QUEUE)
    public void handleSeckillOrder(SeckillOrderDTO orderDTO) {
        log.info("收到秒杀订单创建消息，orderId={}, userId={}, voucherId={}",
                orderDTO.getOrderId(), orderDTO.getUserId(), orderDTO.getVoucherId());
        try {
            seckillService.createSeckillOrder(orderDTO);
            log.info("秒杀订单创建成功，orderId={}", orderDTO.getOrderId());
        } catch (Exception e) {
            log.error("秒杀订单创建失败，orderId={}", orderDTO.getOrderId(), e);
            throw e; // 抛出异常触发重试，重试耗尽后拒绝并经死信交换机转入 seckill.dead.queue
        }
    }

    /**
     * ② 监听关单队列：延迟队列里的消息躺满 15 分钟后被死信交换机转到这里
     * <p>内部 CAS 关单天然幂等：订单已支付 / 已关闭时影响行数为 0，直接跳过、不回补库存。
     * 抛异常会重试 3 次，最终被丢弃也无妨 —— 定时扫表任务会兜底捞回来。</p>
     */
    @RabbitListener(queues = SECKILL_ORDER_CLOSE_QUEUE)
    public void handleTimeoutOrder(SeckillOrderDTO orderDTO) {
        log.info("收到超时订单关单消息，orderId={}, voucherId={}", orderDTO.getOrderId(), orderDTO.getVoucherId());
        try {
            seckillService.closeTimeoutOrder(orderDTO);
        } catch (Exception e) {
            log.error("超时订单关闭失败，orderId={}", orderDTO.getOrderId(), e);
            throw e;
        }
    }

    /**
     * ③ 监听死信队列：建单失败被拒绝的消息
     * <p>此时订单已随事务回滚，CAS 关单必然 0 行，等于安全跳过（幂等）</p>
     */
    @RabbitListener(queues = SECKILL_DEAD_QUEUE)
    public void handleDeadOrder(SeckillOrderDTO orderDTO) {
        log.warn("收到建单失败死信消息，尝试幂等关单，orderId={}", orderDTO.getOrderId());
        try {
            seckillService.closeTimeoutOrder(orderDTO);
        } catch (Exception e) {
            log.error("死信消息处理失败，orderId={}", orderDTO.getOrderId(), e);
            throw e;
        }
    }
}
