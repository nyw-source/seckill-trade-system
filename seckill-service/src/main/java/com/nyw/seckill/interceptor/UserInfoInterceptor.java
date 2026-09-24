package com.nyw.seckill.interceptor;

import cn.hutool.core.util.StrUtil;
import com.nyw.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 解析当前登录用户：从网关透传的 X-User-Id 请求头取值写入 UserContext
 *
 * <p>网关（AuthGlobalFilter）校验 JWT 后把 userId 写进 X-User-Id 头透传给下游，
 * 各微服务自行解析；本服务之前没有这个拦截器，导致 UserContext.getUser() 恒为 null，
 * 秒杀接口直接 NPE。</p>
 */
@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    /** 与网关 AuthGlobalFilter 中写入的请求头保持一致 */
    public static final String USER_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(USER_HEADER);
        if (StrUtil.isBlank(userId)) {
            // 不在这里拦截，交给业务层判断（部分管理/文档接口不需要登录用户）
            log.debug("请求未携带 {} 头，uri={}", USER_HEADER, request.getRequestURI());
            return true;
        }
        try {
            UserContext.setUser(Long.valueOf(userId));
        } catch (NumberFormatException e) {
            log.warn("{} 头不是合法的用户 ID：{}", USER_HEADER, userId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理：Tomcat 线程池会复用线程，不清理会把上一个请求的用户串到下一个请求上
        UserContext.removeUser();
    }
}
