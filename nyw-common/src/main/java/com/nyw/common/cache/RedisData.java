package com.nyw.common.cache;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis 缓存数据包装类，用于逻辑过期方案
 */
@Data
public class RedisData {
    /** 逻辑过期时间 */
    private LocalDateTime expireTime;
    /** 实际缓存数据 */
    private Object data;
}