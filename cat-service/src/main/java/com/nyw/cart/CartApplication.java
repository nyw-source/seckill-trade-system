package com.nyw.cart;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@MapperScan("com.nyw.cart.mapper")
@SpringBootApplication
@EnableFeignClients(basePackages = "com.nyw.api.client")
public class CartApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartApplication.class, args);
    }


    @Bean
    public RestTemplate restTemplate(){
        return  new RestTemplate();
    }
}