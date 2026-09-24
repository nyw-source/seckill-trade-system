package com.nyw.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nyw.seckill.domain.po.SeckillOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * CAS 关闭未支付订单（幂等关单）
     * <p>只有 status = 1（未支付）的订单才能被关闭，由数据库的行锁保证并发安全。</p>
     * <p>返回 0 说明订单不存在、已支付或已被关闭，调用方必须直接 return，
     * 否则消息重复投递会导致库存被重复回补。</p>
     *
     * @param orderId 秒杀订单 ID
     * @return 影响行数，1 = 关闭成功，0 = 无需处理
     */
    @Update("UPDATE seckill_order SET status = 3 WHERE id = #{orderId} AND status = 1")
    int closeIfUnpaid(@Param("orderId") Long orderId);

    /**
     * 查询超时未支付订单（定时扫表兜底）
     * <p>必须走 (status, create_time) 联合索引，否则每 2 分钟一次全表扫描会把数据库拖垮：
     * status 用于等值过滤（区分度低、放前面做前缀），create_time 用于范围过滤。
     * 行锁竞争也靠 LIMIT 限制单次批量。</p>
     *
     * @param deadline 超时判定时间点
     * @param limit    单次最多返回条数
     * @return 待关闭订单列表（按创建时间升序，先超时的先处理）
     */
    @Select("SELECT id, user_id, voucher_id, item_id, status, create_time, pay_time FROM seckill_order " +
            "WHERE status = 1 AND create_time < #{deadline} " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillOrder> selectTimeoutOrders(@Param("deadline") LocalDateTime deadline,
                                           @Param("limit") int limit);
}
