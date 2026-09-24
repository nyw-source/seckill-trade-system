package com.nyw.gateway.filter;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.nyw.gateway.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.util.List;

/**
 * Gateway 全局 JWT 鉴权过滤器
 *
 * <p>身份的唯一来源是服务端对 token 的解析结果，**绝不采信客户端自带的 X-User-Id**：
 * 进过滤器第一步就把请求头里的 X-User-Id 无条件摘掉，解析成功后再按 token 里的 userId 重新写入。
 * 这样即使攻击者手动构造 {@code X-User-Id: 1}，也不可能冒充别人 —— 这是防横向越权的关键。
 *
 * <p>分支规则：
 * <ol>
 *   <li>带 token 且校验通过 → 覆写 X-User-Id 后转发；</li>
 *   <li>带 token 但校验失败 → 白名单路径按匿名放行（不注入身份），其余 401；</li>
 *   <li>不带 token → 调试开关开启且带了 X-User-Id 时透传（仅限本机调试），
 *       否则白名单放行、其余 401。</li>
 * </ol>
 *
 * <p>注意：这里**不能**加 {@code @RequiredArgsConstructor}。本类必须自己写构造器
 * （要拿 KeyPair 现场构造 JWTSigner，而 JWTSigner 本身并不是 Bean），
 * 再加一个 Lombok 生成的构造器就会变成"多个构造器且都没有 @Autowired"，
 * Spring 只能退回去找无参构造器，启动时直接报：
 * {@code Failed to instantiate [AuthGlobalFilter]: No default constructor found}。
 */
@Slf4j
@Component
@EnableConfigurationProperties(AuthProperties.class)
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 下游服务读取用户身份用的请求头 */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final AuthProperties authProperties;
    private final JWTSigner jwtSigner;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AuthGlobalFilter(AuthProperties authProperties, KeyPair keyPair) {
        this.authProperties = authProperties;
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 客户端自带的 X-User-Id：只作为"调试取数"来源，正常链路一律丢弃
        String clientUserId = request.getHeaders().getFirst(USER_ID_HEADER);
        String token = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // 最终要透传给下游的身份；null 表示匿名请求
        String resolvedUserId = null;

        if (token != null && !token.trim().isEmpty()) {
            // 1. 有 token：身份以 token 为准
            try {
                resolvedUserId = parseToken(token).toString();
            } catch (Exception e) {
                // 白名单路径允许匿名访问，token 失效不应该把匿名接口也一起打死
                if (!isExcluded(path)) {
                    log.warn("token 校验失败 path={} reason={}", path, e.getMessage());
                    return unauthorized(exchange, "无效的token");
                }
            }
        } else if (authProperties.isDebugAllowUserHeader() && clientUserId != null && !clientUserId.trim().isEmpty()) {
            // 2. 无 token + 调试开关：放行客户端自带的身份（仅本机调试，生产必须关闭）
            resolvedUserId = clientUserId.trim();
            log.warn("调试开关已开启，透传客户端自带的 X-User-Id={} path={}", resolvedUserId, path);
        } else if (!isExcluded(path)) {
            // 3. 无 token 且不在白名单
            return unauthorized(exchange, "未登录");
        }

        // 4. 先摘掉客户端自带的 X-User-Id，再按解析结果写入，保证身份不可伪造
        ServerHttpRequest.Builder builder = request.mutate()
                .headers(headers -> headers.remove(USER_ID_HEADER));
        if (resolvedUserId != null) {
            builder.header(USER_ID_HEADER, resolvedUserId);
        }
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(builder.build())
                .build();
        return chain.filter(mutatedExchange);
    }

    private boolean isExcluded(String path) {
        List<String> excludePaths = authProperties.getExcludePaths();
        if (excludePaths == null || excludePaths.isEmpty()) {
            return false;
        }
        return excludePaths.stream().anyMatch(p -> antPathMatcher.match(p, path));
    }

    private Long parseToken(String token) {
        // 1. 校验 token 是否为空
        if (token == null) {
            throw new RuntimeException("token为空");
        }
        // 2. 解析 JWT
        JWT jwt;
        try {
            jwt = JWT.of(token).setSigner(jwtSigner);
        } catch (Exception e) {
            throw new RuntimeException("无效的token", e);
        }
        // 3. 校验签名
        if (!jwt.verify()) {
            throw new RuntimeException("无效的token");
        }
        // 4. 校验是否过期
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            throw new RuntimeException("token已过期");
        }
        // 5. 提取 userId
        Object userPayload = jwt.getPayload("user");
        if (userPayload == null) {
            throw new RuntimeException("无效的token");
        }
        try {
            return Long.valueOf(userPayload.toString());
        } catch (RuntimeException e) {
            throw new RuntimeException("无效的token");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
