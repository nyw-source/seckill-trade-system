package com.nyw.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@MapperScan("com.nyw.order.mapper")
// scanBasePackages = "com.nyw"：订单号生成器 DistributedIdGenerator 在 com.nyw.common 包下，
// 默认只扫 com.nyw.order 会注入不到 Bean、启动直接失败
@SpringBootApplication(scanBasePackages = "com.nyw")
@EnableFeignClients(basePackages = "com.nyw.api.client")
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}