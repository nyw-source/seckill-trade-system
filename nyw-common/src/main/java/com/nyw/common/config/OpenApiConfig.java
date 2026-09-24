package com.nyw.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档基础信息（springdoc-openapi3 / knife4j 4.4.0）
 *
 * <p>springfox 时代靠 {@code Docket} Bean + {@code @EnableSwagger2} 组装文档，
 * springdoc 体系下统一是一个 {@link OpenAPI} Bean，而且会自动扫描 Spring MVC 注解，
 * 不再需要手写扫描包路径。
 *
 * <p>标题取 {@code spring.application.name}，所以 6 个服务共用这一份配置也各有各的文档标题。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenAPI.class)
@AutoConfigureBefore(name = "org.springdoc.core.SpringDocConfiguration")
public class OpenApiConfig {

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI nywOpenAPI(@Value("${spring.application.name:nyw}") String appName) {
        return new OpenAPI().info(new Info()
                .title(appName + " 接口文档")
                .description("秒杀交易系统微服务接口文档（springdoc-openapi3 + knife4j 4.4.0）")
                .version("1.0.0")
                .contact(new Contact().name("小牛")));
    }
}
