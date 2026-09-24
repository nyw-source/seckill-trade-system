package com.nyw.order.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "余额支付表单")
public class PayOrderFormDTO {
    private Long id;
    private String pw;
}