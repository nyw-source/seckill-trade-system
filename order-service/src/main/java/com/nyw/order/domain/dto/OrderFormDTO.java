package com.nyw.order.domain.dto;

import com.nyw.api.dto.OrderDetailDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "交易下单表单实体")
public class OrderFormDTO {
    @Schema(description = "收货地址id")
    private Long addressId;
    @Schema(description = "支付类型")
    private Integer paymentType;
    @Schema(description = "下单商品列表")
    private List<OrderDetailDTO> details;
}