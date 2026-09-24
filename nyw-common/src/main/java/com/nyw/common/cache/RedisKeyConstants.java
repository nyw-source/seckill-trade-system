package com.nyw.common.cache;

/**
 * Redis Key 规范常量
 * 格式：项目名:业务模块:数据类型:业务标识
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    /** 项目前缀 */
    public static final String PREFIX = "nyw";

    /** 分隔符 */
    public static final String SEPARATOR = ":";

    /* ========== 缓存相关 ========== */
    /** 缓存空值前缀 */
    public static final String CACHE_NULL = "cache:null";

    /** 互斥锁前缀 */
    public static final String LOCK_MUTEX = "lock:mutex";

    /** 逻辑过期锁前缀 */
    public static final String LOCK_LOGICAL = "lock:logical";

    /** 商品详情缓存前缀 */
    public static final String CACHE_ITEM = "cache:item";

    /* ========== 业务模块 ========== */
    /** 商品 */
    public static final String MODULE_ITEM = "item";

    /** 用户 */
    public static final String MODULE_USER = "user";

    /** 订单 */
    public static final String MODULE_ORDER = "order";

    /** 秒杀 */
    public static final String MODULE_SECKILL = "seckill";

    /* ========== 分布式 ID ========== */
    /** 订单 ID 自增 key */
    public static final String ID_ORDER = PREFIX + SEPARATOR + "id" + SEPARATOR + "order";

    /** 秒杀订单 ID 自增 key */
    public static final String ID_SECKILL_ORDER = PREFIX + SEPARATOR + "id" + SEPARATOR + "seckill:order";

    /* ========== 秒杀相关 ========== */
    /** 秒杀商品库存 */
    public static final String SECKILL_STOCK = PREFIX + SEPARATOR + MODULE_SECKILL + SEPARATOR + "stock";

    /** 秒杀已下单用户集合 */
    public static final String SECKILL_ORDER_SET = PREFIX + SEPARATOR + MODULE_SECKILL + SEPARATOR + "order:set";

    /** 秒杀热点商品缓存（活动开始前预热） */
    public static final String SECKILL_ITEM = PREFIX + SEPARATOR + MODULE_SECKILL + SEPARATOR + "item";

    /* ========== 工具方法 ========== */

    /**
     * 构建秒杀库存 key
     * @param voucherId 秒杀券 ID
     */
    public static String seckillStockKey(Long voucherId) {
        return SECKILL_STOCK + SEPARATOR + voucherId;
    }

    /**
     * 构建秒杀已下单用户集合 key
     * @param voucherId 秒杀券 ID
     */
    public static String seckillOrderSetKey(Long voucherId) {
        return SECKILL_ORDER_SET + SEPARATOR + voucherId;
    }

    /**
     * 构建秒杀热点商品缓存 key
     * @param itemId 商品 ID
     */
    public static String seckillItemKey(Long itemId) {
        return SECKILL_ITEM + SEPARATOR + itemId;
    }

    /**
     * 构建缓存 key
     * @param prefix 业务前缀
     * @param id 业务 ID
     */
    public static String buildCacheKey(String prefix, Object id) {
        return PREFIX + SEPARATOR + prefix + SEPARATOR + id;
    }
}