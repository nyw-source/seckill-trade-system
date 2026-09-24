package com.nyw.common.utils;

import com.nyw.common.cache.RedisKeyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 分布式 ID 生成器
 * <p>基于 Redis INCR + 时间戳，生成全局唯一、趋势递增的 ID</p>
 *
 * <p>结构：ID = (秒级时间戳 &lt;&lt; 32) | (Redis 自增序号 &amp; 0xFFFFFFFFL)
 * —— 高位 32 位是时间戳（趋势递增、可按时间排序），低 32 位是同一秒内的序号（Redis 保证唯一）</p>
 *
 * <p><b>为什么不直接拼「14 位时间戳 + 6 位序号」：</b>
 * 那样拼出来是 20 位十进制数（如 {@code 20260922170617000001} ≈ 2.0×10^19），
 * 已经超过 {@code long} / MySQL {@code BIGINT} 的有符号上限 9223372036854775807（19 位），
 * {@code Long.parseLong} 会直接抛 {@code NumberFormatException}，落库也会 {@code DataError 1264 Out of range}。
 * 左移 32 位后整体不超过 19 位，BIGINT 安全（2038-01-19 之前）。</p>
 */
@Slf4j
@Component
// 注意：这里原本是 @ConditionalOnBean(StringRedisTemplate.class)，是错误的用法 ——
// 组件扫描在自动配置之前执行，判断条件时容器里还没有 StringRedisTemplate，
// 条件恒为 false，Bean 永远注册不上，注入方启动即报 NoSuchBeanDefinitionException。
// 想表达「有 Redis 才启用」，应该用 @ConditionalOnClass（扫描期就能判定），
// 或者把 Bean 挪到 spring.factories 的自动配置类里注册。
@ConditionalOnClass(StringRedisTemplate.class)
public class DistributedIdGenerator {

    private final StringRedisTemplate stringRedisTemplate;

    public DistributedIdGenerator(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成下一个订单 ID（时间戳左移 32 位 + Redis 序列号）
     */
    public long nextOrderId() {
        return nextId(RedisKeyConstants.ID_ORDER);
    }

    /**
     * 生成下一个秒杀订单 ID
     */
    public long nextSeckillOrderId() {
        return nextId(RedisKeyConstants.ID_SECKILL_ORDER);
    }

    /**
     * 根据 key 生成下一个 ID
     *
     * @param key Redis INCR key
     * @return 分布式 ID，不超过 19 位，BIGINT 安全
     */
    public long nextId(String key) {
        // 1. 秒级时间戳放高位
        long timestamp = Instant.now().getEpochSecond();
        // 2. Redis INCR 取同秒内序号放低位
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        if (seq == null) {
            throw new RuntimeException("Redis INCR 失败，key=" + key);
        }
        // 3. 时间戳左移 32 位 + 序号（只取低 32 位，防止长时间运行后越位污染时间戳部分）
        return (timestamp << 32) | (seq & 0xFFFFFFFFL);
    }
}