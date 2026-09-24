package com.nyw.cart.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "新增购物车商品表单实体")
public class CartFormDTO {
    @Schema(description = "商品id")
    private Long itemId;
    @Schema(description = "商品标题")
    private String name;
    @Schema(description = "商品动态属性键值集")
    private String spec;
    @Schema(description = "价格,单位：分")
    private Integer price;
    @Schema(description = "商品图片")
    private String image;
}
