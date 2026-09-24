package com.nyw.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// scanBasePackages = "com.nyw"：nyw-common 里的 DistributedIdGenerator / CacheServiceImpl
// 在 com.nyw.common 包下，默认只扫 com.nyw.seckill 会注入不到 Bean、启动直接失败
@SpringBootApplication(scanBasePackages = "com.nyw")
@EnableFeignClients(basePackages = "com.nyw.api.client")
@MapperScan("com.nyw.seckill.mapper")
@EnableScheduling
public class SeckillApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}