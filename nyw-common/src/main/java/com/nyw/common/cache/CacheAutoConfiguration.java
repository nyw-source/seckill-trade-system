package com.nyw.common.cache;

import com.nyw.common.cache.impl.CacheServiceImpl;
import com.nyw.common.config.RedisConfig;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存服务的自动配置
 *
 * <p>为什么不用 {@code @Component} + 组件扫描：
 * {@code CacheServiceImpl} 在 nyw-common 里，而各业务服务的 {@code @SpringBootApplication}
 * 默认只扫描自己所在的包（如 {@code com.nyw.item}），扫不到 {@code com.nyw.common}。
 * 只有 seckill-service / order-service 恰好写了 {@code scanBasePackages = "com.nyw"} 才能扫到，
 * 其余服务即使条件成立也拿不到这个 Bean —— 这属于"配置靠运气"。
 *
 * <p>改成自动配置后，所有依赖 nyw-common 的服务行为一致，不依赖各自的扫描范围。
 * {@code @AutoConfigureAfter(RedisConfig.class)} 保证 {@code RedisTemplate<String, Object>}
 * 已经注册完成，避免注入时找不到 Bean。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({RedisTemplate.class, StringRedisTemplate.class})
@AutoConfigureAfter(RedisConfig.class)
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CacheService.class)
    public CacheService cacheService(StringRedisTemplate stringRedisTemplate,
                                     RedisTemplate<String, Object> redisTemplate) {
        return new CacheServiceImpl(stringRedisTemplate, redisTemplate);
    }
}
