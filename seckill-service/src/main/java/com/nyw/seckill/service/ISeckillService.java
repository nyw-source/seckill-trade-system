package com.nyw.seckill.service;

import com.nyw.api.dto.ItemDTO;
import com.nyw.seckill.domain.dto.SeckillOrderDTO;
import com.nyw.seckill.domain.po.SeckillOrder;

import java.time.LocalDateTime;
import java.util.List;

public interface ISeckillService {

    /**
     * 执行秒杀
     * @param voucherId 秒杀券 ID
     * @return 秒杀订单 ID
     */
    Long seckillVoucher(Long voucherId);

    /**
     * 初始化秒杀库存到 Redis
     * @param voucherId 秒杀券 ID
     */
    void initStock(Long voucherId);

    /**
     * 预热单场秒杀：库存 key + 关联的热点商品缓存
     * <p>活动开始前手动 / 脚本触发一次即可</p>
     *
     * @param voucherId 秒杀券 ID
     */
    void preheat(Long voucherId);

    /**
     * 批量预热秒杀场次：正在进行中 + 未来 30 分钟内即将开始的场次
     *
     * @return 本次预热的场次数量
     */
    int preheatUpcoming();

    /**
     * 查询秒杀热点商品：优先读预热缓存，未命中回源商品服务并回填
     *
     * @param itemId 商品 ID
     * @return 商品信息，不存在返回 null
     */
    ItemDTO querySeckillItem(Long itemId);

    /**
     * 异步创建秒杀订单（MQ 消费者调用）
     * <p>必须通过 Spring 代理跨 Bean 调用，否则 @Transactional 失效</p>
     *
     * @param orderDTO 秒杀订单消息
     */
    void createSeckillOrder(SeckillOrderDTO orderDTO);

    /**
     * 关闭超时未支付订单（关单队列消费者 / 定时扫表任务调用）
     * <p>CAS 幂等：订单已支付或已关闭时影响行数为 0，直接跳过且不回补库存，
     * 所以延迟消息重复投递、与扫表任务并发执行都不会把库存补多</p>
     *
     * @param orderDTO 秒杀订单消息
     */
    void closeTimeoutOrder(SeckillOrderDTO orderDTO);

    /**
     * 查询已超时但仍未支付的订单（定时扫表兜底用）
     *
     * @param deadline 超时判定时间点：create_time 早于该时间且仍未支付即为超时
     * @param limit    单次最多返回条数
     * @return 待关闭的订单列表
     */
    List<SeckillOrder> findTimeoutOrders(LocalDateTime deadline, int limit);
}
