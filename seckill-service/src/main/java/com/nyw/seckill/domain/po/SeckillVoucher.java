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
@TableName("seckill_voucher")
public class SeckillVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 秒杀券 ID */
    @TableId(value = "voucher_id", type = IdType.INPUT)
    private Long voucherId;

    /** 关联商品 ID */
    private Long itemId;

    /** 秒杀库存 */
    private Integer stock;

    /** 秒杀开始时间 */
    private LocalDateTime beginTime;

    /** 秒杀结束时间 */
    private LocalDateTime endTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}