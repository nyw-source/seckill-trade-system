package com.nyw.item.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "商品实体")
public class ItemDTO {
    @Schema(description = "商品id")
    private Long id;
    @Schema(description = "SKU名称")
    private String name;
    @Schema(description = "价格（分）")
    private Integer price;
    @Schema(description = "库存数量")
    private Integer stock;
    @Schema(description = "商品图片")
    private String image;
    @Schema(description = "类目名称")
    private String category;
    @Schema(description = "品牌名称")
    private String brand;
    @Schema(description = "规格")
    private String spec;
    @Schema(description = "销量")
    private Integer sold;
    @Schema(description = "评论数")
    private Integer commentCount;
    @Schema(description = "是否是推广广告，true/false")
    private Boolean isAD;
    @Schema(description = "商品状态 1-正常，2-下架，3-删除")
    private Integer status;
}
