package com.nyw.seckill.config;

import cn.hutool.core.util.StrUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * Redis 密码
     * <p>必须显式传给 Redisson：只配了地址不传密码，连接带密码的 Redis 会直接抛
     * {@code RedisAuthRequiredException: NOAUTH Authentication required}，服务启动失败。
     * 另外密码为空时要传 null 而不是空字符串，否则 Redisson 仍会发送 AUTH 命令。</p>
     */
    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${spring.redis.database:0}")
    private int redisDatabase;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setPassword(StrUtil.isBlank(redisPassword) ? null : redisPassword)
                .setDatabase(redisDatabase);
        return Redisson.create(config);
    }
}
