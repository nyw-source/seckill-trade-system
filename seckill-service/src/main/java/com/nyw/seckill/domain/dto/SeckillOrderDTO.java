package com.nyw.seckill.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单消息 DTO，用于 RabbitMQ 异步创建订单
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 秒杀订单 ID */
    private Long orderId;

    /** 秒杀券 ID */
    private Long voucherId;

    /** 用户 ID */
    private Long userId;

    /** 商品 ID */
    private Long itemId;
}