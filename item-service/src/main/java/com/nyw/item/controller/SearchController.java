package com.nyw.item.controller;

import com.nyw.common.domain.PageDTO;
import com.nyw.item.domain.dto.ItemDTO;
import com.nyw.item.domain.query.ItemPageQuery;
import com.nyw.item.service.IItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商品搜索接口（原 nyw-service 单体 SearchController 迁移至此）。
 * 单体版本经 Feign 调 item-service 再手工组装 PageDTO；
 * 现在与商品数据同服务，直接本地调用 searchItems。
 */
@Tag(name = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final IItemService itemService;

    @Operation(summary = "搜索商品")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query) {
        Map<String, Object> result = itemService.searchItems(query);
        PageDTO<ItemDTO> pageDTO = new PageDTO<>();
        pageDTO.setTotal((Long) result.get("total"));
        pageDTO.setPages((Long) result.get("pages"));
        @SuppressWarnings("unchecked")
        java.util.List<ItemDTO> list = (java.util.List<ItemDTO>) result.get("list");
        pageDTO.setList(list);
        return pageDTO;
    }
}
