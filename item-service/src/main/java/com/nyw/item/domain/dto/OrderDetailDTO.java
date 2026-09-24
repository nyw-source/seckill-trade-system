package com.nyw.item.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

@Schema(description = "订单明细条目")
@Data
@Accessors(chain = true)
public class OrderDetailDTO {
    @Schema(description = "商品id")
    private Long itemId;
    @Schema(description = "商品购买数量")
    private Integer num;
}
