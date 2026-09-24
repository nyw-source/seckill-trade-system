package com.nyw.item.domain.query;

import com.nyw.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "商品分页查询条件")
public class ItemPageQuery extends PageQuery {
    @Schema(description = "搜索关键字")
    private String key;
    @Schema(description = "商品分类")
    private String category;
    @Schema(description = "商品品牌")
    private String brand;
    @Schema(description = "价格最小值")
    private Integer minPrice;
    @Schema(description = "价格最大值")
    private Integer maxPrice;
}
