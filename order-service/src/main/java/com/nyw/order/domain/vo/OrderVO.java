package com.nyw.order.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderVO {
    private Long id;
    private Integer totalFee;
    private Integer paymentType;
    private Long userId;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime consignTime;
    private LocalDateTime endTime;
    private LocalDateTime closeTime;
    private LocalDateTime commentTime;
    private LocalDateTime updateTime;
}