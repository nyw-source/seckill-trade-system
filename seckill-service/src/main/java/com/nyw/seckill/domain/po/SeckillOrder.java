package com.nyw.seckill.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_order")
public class SeckillOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 ID */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 秒杀券 ID */
    private Long voucherId;

    /** 商品 ID */
    private Long itemId;

    /** 订单状态：1=未支付, 2=已支付, 3=已关闭 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 支付时间 */
    private LocalDateTime payTime;
}