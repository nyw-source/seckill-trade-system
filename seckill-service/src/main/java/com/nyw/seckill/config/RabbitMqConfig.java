package com.nyw.seckill.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀 MQ 拓扑（延迟队列 + 死信交换机实现「15 分钟未支付自动关单」）
 *
 * <pre>
 * ① 建单链路（立即生效）
 *    seckill.order.exchange --(seckill.order.create)--> seckill.order.queue
 *        --> SeckillOrderListener#handleSeckillOrder --> SeckillServiceImpl#createSeckillOrder
 *
 * ② 关单链路（延迟 15 分钟）
 *    seckill.order.delay.exchange --(order.delay)--> seckill.order.delay.queue   ← 故意不注册消费者，只躺 TTL
 *        --TTL 到期--> seckill.order.close.exchange --(order.close)--> seckill.order.close.queue
 *        --> SeckillOrderListener#handleTimeoutOrder --> SeckillServiceImpl#closeTimeoutOrder
 *
 * ③ 建单失败兜底
 *    seckill.order.queue 中被拒绝的消息（重试 3 次仍失败）--(seckill.dead.close)--> seckill.dead.queue
 *        --> SeckillOrderListener#handleDeadOrder（订单已回滚，CAS 关单 0 行，天然幂等跳过）
 * </pre>
 *
 * <p><b>为什么延迟队列必须独立、而且不能有消费者：</b>消费者 ack 之后消息就被删除，
 * 消息根本不会在队列里停留，也就永远等不到 TTL 到期、永远进不了死信交换机。
 * 「有消费者的队列上配 TTL」是「15 分钟未支付自动关单」失效的经典原因。</p>
 *
 * <p><b>为什么用队列级 TTL（x-message-ttl）而不是消息级 TTL：</b>队列级 TTL 对队列内所有消息一视同仁，
 * 到期顺序 == 入队顺序，不存在队头阻塞；若给每条消息单独设 expiration，
 * 队头那条不满足条件时，后面已到期的消息也得干等（面试常问的坑）。</p>
 */
@Configuration
public class RabbitMqConfig {

    /** 订单超时时间（分钟）：未支付订单在延迟队列里躺满该时长后触发自动关单 */
    public static final int DEFAULT_ORDER_TIMEOUT_MINUTES = 15;

    /** 联调/演示时可用 nyw.seckill.order-timeout-minutes 压短（例如 1 分钟），生产用默认 15 分钟 */
    @Value("${nyw.seckill.order-timeout-minutes:" + DEFAULT_ORDER_TIMEOUT_MINUTES + "}")
    private int orderTimeoutMinutes;

    /* ========== ① 建单 ========== */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    /* ========== ② 延迟（只压 TTL，无消费者） ========== */
    public static final String SECKILL_ORDER_DELAY_EXCHANGE = "seckill.order.delay.exchange";
    public static final String SECKILL_ORDER_DELAY_QUEUE = "seckill.order.delay.queue";
    public static final String SECKILL_ORDER_DELAY_ROUTING_KEY = "order.delay";

    /* ========== ② 关单（延迟队列 TTL 到期后转入） ========== */
    public static final String SECKILL_ORDER_CLOSE_EXCHANGE = "seckill.order.close.exchange";
    public static final String SECKILL_ORDER_CLOSE_QUEUE = "seckill.order.close.queue";
    public static final String SECKILL_ORDER_CLOSE_ROUTING_KEY = "order.close";

    /* ========== ③ 建单失败兜底死信 ========== */
    public static final String SECKILL_DEAD_QUEUE = "seckill.dead.queue";
    public static final String SECKILL_DEAD_EXCHANGE = "seckill.dead.exchange";
    public static final String SECKILL_DEAD_ROUTING_KEY = "seckill.dead.close";

    // ==================== ① 建单 ====================

    /**
     * 秒杀订单交换机
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(SECKILL_ORDER_EXCHANGE);
    }

    /**
     * 秒杀订单队列：有消费者，消息进来立刻被消费并 ack，所以**不能设 TTL**
     * <p>保留死信交换机：建单失败消息被拒绝（default-requeue-rejected=false）后转入 seckill.dead.queue</p>
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .deadLetterExchange(SECKILL_DEAD_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 秒杀订单队列绑定
     */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    // ==================== ② 延迟 ====================

    /**
     * 延迟交换机：只负责把延迟消息路由到延迟队列
     */
    @Bean
    public DirectExchange seckillOrderDelayExchange() {
        return new DirectExchange(SECKILL_ORDER_DELAY_EXCHANGE);
    }

    /**
     * 延迟队列：故意**不注册消费者**，消息只能在这里躺满 TTL
     * <p>TTL 到期后由死信交换机转发到关单队列，触发超时关单</p>
     */
    @Bean
    public Queue seckillOrderDelayQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_DELAY_QUEUE)
                .ttl(orderTimeoutMinutes * 60 * 1000)
                .deadLetterExchange(SECKILL_ORDER_CLOSE_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_ORDER_CLOSE_ROUTING_KEY)
                .build();
    }

    /**
     * 延迟队列绑定
     */
    @Bean
    public Binding seckillOrderDelayBinding() {
        return BindingBuilder.bind(seckillOrderDelayQueue())
                .to(seckillOrderDelayExchange())
                .with(SECKILL_ORDER_DELAY_ROUTING_KEY);
    }

    // ==================== ② 关单 ====================

    /**
     * 关单交换机：接收延迟队列 TTL 到期后死信出来的消息
     */
    @Bean
    public DirectExchange seckillOrderCloseExchange() {
        return new DirectExchange(SECKILL_ORDER_CLOSE_EXCHANGE);
    }

    /**
     * 关单队列：由 SeckillOrderListener#handleTimeoutOrder 消费
     * <p>关单逻辑内部 CAS 幂等（只有 status=1 能关），所以消息重复投递、与扫表任务并发都不怕</p>
     */
    @Bean
    public Queue seckillOrderCloseQueue() {
        return new Queue(SECKILL_ORDER_CLOSE_QUEUE, true);
    }

    /**
     * 关单队列绑定
     */
    @Bean
    public Binding seckillOrderCloseBinding() {
        return BindingBuilder.bind(seckillOrderCloseQueue())
                .to(seckillOrderCloseExchange())
                .with(SECKILL_ORDER_CLOSE_ROUTING_KEY);
    }

    // ==================== ③ 建单失败兜底 ====================

    @Bean
    public DirectExchange seckillDeadExchange() {
        return new DirectExchange(SECKILL_DEAD_EXCHANGE);
    }

    @Bean
    public Queue seckillDeadQueue() {
        return new Queue(SECKILL_DEAD_QUEUE, true);
    }

    @Bean
    public Binding seckillDeadBinding() {
        return BindingBuilder.bind(seckillDeadQueue())
                .to(seckillDeadExchange())
                .with(SECKILL_DEAD_ROUTING_KEY);
    }
}
