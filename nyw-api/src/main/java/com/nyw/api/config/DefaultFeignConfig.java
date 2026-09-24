package com.nyw.api.config;

import feign.Logger;
import org.springframework.context.annotation.Bean;

public class DefaultFeignConfig {
    @Bean
    public Logger.Level feignLOggerLevel(){
        return Logger.Level.FULL;
    }
}
