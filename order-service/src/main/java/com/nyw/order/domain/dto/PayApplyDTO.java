package com.nyw.order.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "支付申请表单")
public class PayApplyDTO {
    @Schema(description = "业务订单号")
    private Long bizOrderNo;
    @Schema(description = "支付渠道编码")
    private String payChannelCode;
    @Schema(description = "支付类型")
    private Integer payType;
}