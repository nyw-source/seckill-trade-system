package com.nyw.common.cache;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存服务接口
 * 封装防穿透、防击穿、防雪崩能力
 */
public interface CacheService {

    /**
     * 缓存穿透保护：查询缓存，未命中查 DB，DB 返回 null 时缓存空值
     *
     * @param keyPrefix  缓存 key 前缀
     * @param id         业务 ID
     * @param type       返回值类型
     * @param dbFallback DB 查询回调
     * @param ttl        缓存过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID 泛型
     * @return 查询结果，可能为 null
     */
    <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit);

    /**
     * 缓存击穿保护（互斥锁）：热点 key 过期时，仅一个线程重建缓存，其余自旋等待
     *
     * @param keyPrefix  缓存 key 前缀
     * @param id         业务 ID
     * @param type       返回值类型
     * @param dbFallback DB 查询回调
     * @param ttl        缓存过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID 泛型
     * @return 查询结果
     */
    <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit);

    /**
     * 缓存击穿保护（逻辑过期）：热点 key 逻辑过期后异步重建，返回旧数据保证可用
     *
     * @param keyPrefix  缓存 key 前缀
     * @param id         业务 ID
     * @param type       返回值类型
     * @param dbFallback DB 查询回调
     * @param ttl        缓存逻辑过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID 泛型
     * @return 查询结果
     */
    <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit);

    /**
     * 写入缓存（带随机 TTL 防雪崩）
     *
     * @param key   Redis key
     * @param value 缓存值
     * @param ttl   基础过期时间
     * @param unit  时间单位
     */
    void set(String key, Object value, Long ttl, TimeUnit unit);

    /**
     * 构建缓存 key
     *
     * @param prefix 业务前缀
     * @param id     业务 ID
     * @return 完整的 Redis key
     */
    String buildKey(String prefix, Object id);

    /**
     * 删除缓存（Cache-Aside 写路径）
     * 数据变更后调用，避免缓存与 DB 长期不一致
     *
     * @param keyPrefix 缓存 key 前缀
     * @param id        业务 ID
     */
    void evict(String keyPrefix, Object id);
}