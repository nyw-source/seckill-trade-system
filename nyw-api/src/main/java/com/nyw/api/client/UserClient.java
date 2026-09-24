package com.nyw.api.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient("user-service")
public interface UserClient {

    @PutMapping("/users/money/deduct/{userId}")
    void deductMoney(@PathVariable("userId") Long userId, @RequestParam("amount") Integer amount);
}