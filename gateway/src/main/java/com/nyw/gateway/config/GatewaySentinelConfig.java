package com.nyw.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Set;

/**
 * Sentinel 网关流控配置
 *
 * <p>这里**只**放"自定义"的东西：限流规则。限流响应体走 yaml 的
 * {@code spring.cloud.sentinel.scg.fallback.*}（mode=response + 429，见 application.yaml）。
 *
 * <p>两个历史教训（勿回退）：
 * <ul>
 * <li>不要再手工 new {@code SentinelGatewayFilter} / {@code SentinelGatewayBlockExceptionHandler}：
 * Spring Cloud Alibaba 的 {@code SentinelSCGAutoConfiguration} 已经用
 * {@code @ConditionalOnMissingBean} 注册了这两个 Bean，本地再定义一个同名 Bean
 * 会直接抛 BeanDefinitionOverrideException，网关根本起不来（本类最初就是因此导致
 * APPLICATION FAILED TO START）。</li>
 * <li>注册 {@code BlockRequestHandler} Bean 也没用：自动配置的 {@code initFallback()}
 * 让 {@code FallbackProperties} 优先，Bean 里的 handler 不会被调用，表现为被限流请求
 * 返回 200 空响应体而非 429（2026-09-24 实测踩过）。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class GatewaySentinelConfig {

    /**
     * 秒杀路由 QPS 阈值（网关层）。
     * <p>可配置的原因：做容量/超卖压测时必须能把「限流保护」和「业务正确性」分开测 ——
     * 阈值留在 500 时，3000 并发里会有一大批被 429 挡在门外，
     * 测出来的是「限流器生效」，而不是「秒杀服务本身不超卖」。</p>
     */
    @Value("${nyw.gateway.flow.seckill-qps:500}")
    private int seckillQps;

    /** 订单路由 QPS 阈值（网关层） */
    @Value("${nyw.gateway.flow.order-qps:200}")
    private int orderQps;

    /**
     * 初始化网关流控规则
     */
    @PostConstruct
    public void initGatewayRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 秒杀接口限流（网关层）
        rules.add(new GatewayFlowRule("seckill-service")
                .setResourceMode(1)  // RESOURCE_MODE_ROUTE_ID = 1
                .setCount(seckillQps)
                .setIntervalSec(1));

        // 订单接口限流（网关层）
        rules.add(new GatewayFlowRule("order-service")
                .setResourceMode(1)  // RESOURCE_MODE_ROUTE_ID = 1
                .setCount(orderQps)
                .setIntervalSec(1));

        GatewayRuleManager.loadRules(rules);
        log.warn("网关流控规则已加载：seckill-service={} QPS，order-service={} QPS",
                seckillQps, orderQps);
    }
}
