package com.nyw.common.cache.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.nyw.common.cache.CacheService;
import com.nyw.common.cache.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存服务实现
 * 封装：空值缓存防穿透、互斥锁防击穿、逻辑过期防击穿、随机 TTL 防雪崩
 *
 * <p>两个 Redis 客户端的分工（不是重复注入）：
 * <ul>
 *   <li>{@link RedisTemplate}：存业务对象，value 走 JSON 序列化（带 {@code @class}），
 *       读出来就是对象，不需要调用方自己 toBean；</li>
 *   <li>{@link StringRedisTemplate}：只用来加/释放互斥锁，SETNX 的值是 "1"，
 *       纯字符串语义，用 String 序列化最直观。</li>
 * </ul>
 *
 * <p>本类由 {@code CacheAutoConfiguration} 通过 {@code @Bean} 注册，
 * 不依赖组件扫描（各业务服务默认扫不到 com.nyw.common 包）。
 */
@Slf4j
public class CacheServiceImpl implements CacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 缓存重建线程池 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /** 空值缓存 TTL（秒） */
    private static final long NULL_TTL = 30L;

    /** 互斥锁 TTL（秒） */
    private static final long LOCK_TTL = 10L;

    /** 互斥锁重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 50L;

    /** 互斥锁 key 前缀 */
    private static final String LOCK_MUTEX_PREFIX = "lock:mutex:";

    /** 逻辑过期锁 key 前缀 */
    private static final String LOCK_LOGICAL_PREFIX = "lock:logical:";

    public CacheServiceImpl(StringRedisTemplate stringRedisTemplate,
                            RedisTemplate<String, Object> redisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisTemplate = redisTemplate;
    }

    // ==================== 穿透保护 ====================

    @Override
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String key = buildKey(keyPrefix, id);
        // 1. 查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        // 2. 命中真实数据 → 直接返回
        if (cached != null && !isNullMarker(cached)) {
            return type.cast(cached);
        }
        // 3. 命中空值缓存 → 返回 null（挡掉穿透请求，不再打 DB）
        if (isNullMarker(cached)) {
            return null;
        }
        // 4. 查 DB
        R result = dbFallback.apply(id);
        // 5. DB 无数据 → 缓存空值防穿透
        if (result == null) {
            redisTemplate.opsForValue().set(key, "", NULL_TTL, TimeUnit.SECONDS);
            return null;
        }
        // 6. 写缓存（随机 TTL 防雪崩）
        this.set(key, result, ttl, unit);
        return result;
    }

    // ==================== 击穿保护（互斥锁） ====================

    @Override
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String key = buildKey(keyPrefix, id);
        String lockKey = LOCK_MUTEX_PREFIX + key;
        // 1. 查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !isNullMarker(cached)) {
            return type.cast(cached);
        }
        // 2. 缓存未命中 → 尝试获取互斥锁 + 重建
        try {
            while (true) {
                boolean locked = tryLock(lockKey);
                if (locked) {
                    try {
                        // Double Check：拿到锁后可能已被别的线程重建
                        cached = redisTemplate.opsForValue().get(key);
                        if (cached != null && !isNullMarker(cached)) {
                            return type.cast(cached);
                        }
                        // 查 DB
                        R result = dbFallback.apply(id);
                        if (result == null) {
                            return null;
                        }
                        // 写缓存
                        this.set(key, result, ttl, unit);
                        return result;
                    } finally {
                        unlock(lockKey);
                    }
                }
                // 未获锁 → 休眠后重试
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("缓存互斥锁等待被中断", e);
        }
    }

    // ==================== 击穿保护（逻辑过期） ====================

    @Override
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String key = buildKey(keyPrefix, id);
        String lockKey = LOCK_LOGICAL_PREFIX + key;
        // 1. 查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        // 2. 未命中 → 直接返回 null（逻辑过期方案下缓存是预热好的，未命中就是不存在的热点数据）
        if (!(cached instanceof RedisData)) {
            return null;
        }
        // 3. 解析 RedisData
        RedisData redisData = (RedisData) cached;
        R result = type.cast(redisData.getData());
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 未过期 → 直接返回
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return result;
        }
        // 5. 已过期 → 尝试获取锁，成功则异步重建（不阻塞当前请求）
        boolean locked = tryLock(lockKey);
        if (locked) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查 DB
                    R newResult = dbFallback.apply(id);
                    // 写入 RedisData
                    this.setWithLogicalExpire(key, newResult, ttl, unit);
                } catch (Exception e) {
                    log.error("逻辑过期缓存重建失败 key={}", key, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 6. 返回旧数据，保证可用性
        return result;
    }

    // ==================== 写入缓存（随机 TTL） ====================

    @Override
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        long ttlSeconds = unit.toSeconds(ttl);
        long randomTtl = randomTtl(ttlSeconds);
        redisTemplate.opsForValue().set(key, value, randomTtl, TimeUnit.SECONDS);
    }

    /**
     * 写入缓存（逻辑过期模式）
     *
     * <p>逻辑过期不在 Redis 上设 TTL（由业务字段 expireTime 控制），
     * 这样数据"物理上还在"，热点 key 不会在同一时刻集体消失。
     */
    public void setWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)));
        redisTemplate.opsForValue().set(key, redisData);
    }

    // ==================== 工具方法 ====================

    @Override
    public String buildKey(String prefix, Object id) {
        return prefix + ":" + id;
    }

    @Override
    public void evict(String keyPrefix, Object id) {
        if (id == null) {
            return;
        }
        String key = buildKey(keyPrefix, id);
        redisTemplate.delete(key);
        log.debug("缓存已失效 key={}", key);
    }

    /**
     * 判断是否为空值缓存标记
     * 约定：DB 查不到时写入空字符串，用来区分"缓存里没有这个 key"（null）与"查过 DB 确实不存在"（""）
     */
    private boolean isNullMarker(Object cached) {
        return cached instanceof CharSequence && StrUtil.isBlank((CharSequence) cached);
    }

    /**
     * 生成随机 TTL（防雪崩）
     * 公式：baseSeconds * (0.8 + random(0.4))
     * 即：基础值 × 80%~120% 的随机波动
     */
    private long randomTtl(long baseSeconds) {
        double factor = 0.8 + Math.random() * 0.4;
        return Math.max(1, (long) (baseSeconds * factor));
    }

    /**
     * 尝试获取互斥锁（SETNX）
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
