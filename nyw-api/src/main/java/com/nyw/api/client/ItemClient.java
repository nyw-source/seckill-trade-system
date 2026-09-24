package com.nyw.api.client;

import com.nyw.api.dto.ItemDTO;
import com.nyw.api.dto.OrderDetailDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@FeignClient("item-service")
public interface ItemClient {

    @GetMapping("/items")
    List<ItemDTO> queryItemByIds(@RequestParam("ids") Collection<Long> ids);

    @PutMapping("/items/stock/deduct")
    void deductStock(@RequestBody List<OrderDetailDTO> items);

    @GetMapping("/items/search")
    Map<String, Object> searchItems(@RequestParam("key") String key,
                                     @RequestParam("brand") String brand,
                                     @RequestParam("category") String category,
                                     @RequestParam("minPrice") Integer minPrice,
                                     @RequestParam("maxPrice") Integer maxPrice,
                                     @RequestParam("pageNo") Integer pageNo,
                                     @RequestParam("pageSize") Integer pageSize);
}