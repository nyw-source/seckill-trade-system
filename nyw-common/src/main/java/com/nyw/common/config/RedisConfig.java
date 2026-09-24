package com.nyw.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 通用 Redis 配置
 *
 * <p>为什么需要这个类：
 * Spring Boot 的 {@code RedisAutoConfiguration} 只提供 {@code RedisTemplate<Object, Object>}
 * 与 {@code StringRedisTemplate} 两个 Bean，**不提供** {@code RedisTemplate<String, Object>}。
 * 所以凡是按 {@code RedisTemplate<String, Object>} 注入的地方（如 {@code CacheServiceImpl}）
 * 都会因为容器里找不到这个泛型 Bean 而失败。
 *
 * <p>为什么用 {@code @AutoConfigureBefore} 而不是 {@code @ConditionalOnBean}：
 * 自动配置类之间是有确定顺序的（由 {@code AutoConfigurationSorter} 排序），
 * 因此在这里使用 {@code @ConditionalOnMissingBean} 是可靠的；
 * 而 @ConditionalOnBean 写在被组件扫描的 @Component 上是不可靠的 —— 组件扫描发生在自动配置之前，
 * 判断条件时 RedisTemplate 还没被注册，条件恒为 false，Bean 永远注册不上。
 *
 * <p>把本类登记进 {@code META-INF/spring.factories} 的 EnableAutoConfiguration，
 * 好处是不依赖各业务服务的 {@code scanBasePackages} —— item-service 这类只扫描自己包的服务同样生效。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({RedisTemplate.class, RedisConnectionFactory.class})
@AutoConfigureBefore(RedisAutoConfiguration.class)
public class RedisConfig {

    /**
     * 字符串 key + JSON value 的 RedisTemplate
     *
     * <p>key 用 String 序列化，保证在 redis-cli 里能直接看到可读的 key；
     * value 用 {@link GenericJackson2JsonRedisSerializer}，序列化结果里带 {@code @class} 类型信息，
     * 反序列化时能还原成真实类型，调用方可以直接拿到对象而不用自己 toBean。
     *
     * <p>注意必须补上 {@link JavaTimeModule}：序列化器默认的 ObjectMapper 不认识
     * {@code LocalDateTime}（{@code RedisData.expireTime}、带时间字段的 DTO 都会踩），
     * 不注册会直接抛 {@code InvalidDefinitionException}。
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 打开默认类型信息：反序列化时按 @class 还原真实类型，否则会退化成 LinkedHashMap
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
