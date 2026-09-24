package com.nyw.seckill.controller;

import com.nyw.api.dto.ItemDTO;
import com.nyw.seckill.service.ISeckillService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "秒杀接口")
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final ISeckillService seckillService;

    @Operation(summary = "秒杀下单")
    @PostMapping("/{voucherId}")
    public Long seckillVoucher(@PathVariable("voucherId") Long voucherId) {
        return seckillService.seckillVoucher(voucherId);
    }

    @Operation(summary = "初始化秒杀库存（管理接口，已升级为预热库存 + 关联商品）")
    @PostMapping("/init-stock/{voucherId}")
    public String initStock(@PathVariable("voucherId") Long voucherId) {
        seckillService.initStock(voucherId);
        return "ok";
    }

    @Operation(summary = "预热单场秒杀：库存 + 关联商品（活动开始前手动/脚本触发）")
    @PostMapping("/preheat/{voucherId}")
    public String preheat(@PathVariable("voucherId") Long voucherId) {
        seckillService.preheat(voucherId);
        return "ok";
    }

    @Operation(summary = "批量预热即将开始的秒杀场次（未来 30 分钟 + 正在进行的场次）")
    @PostMapping("/preheat")
    public String preheatUpcoming() {
        return "preheated vouchers: " + seckillService.preheatUpcoming();
    }

    @Operation(summary = "查询秒杀热点商品（优先读预热缓存）")
    @GetMapping("/item/{itemId}")
    public ItemDTO querySeckillItem(@PathVariable("itemId") Long itemId) {
        return seckillService.querySeckillItem(itemId);
    }
}
