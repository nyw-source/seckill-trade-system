package com.nyw.gateway.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;
import java.util.List;

@Data
@Slf4j
@ConfigurationProperties(prefix = "hm.auth")
public class AuthProperties {

    /** 放行白名单（Ant 风格路径），这些路径允许匿名访问 */
    private List<String> excludePaths;

    /**
     * 调试开关：是否允许"无 token + 客户端自带 X-User-Id"直接透传身份。
     *
     * <p><b>默认必须为 false</b>。打开等于任何人写一个请求头就能冒充任意用户，
     * 属于典型的横向越权后门，只能在本机/dev 排查问题时临时开。
     * 打开时启动日志会打印醒目告警，避免误带到生产。
     */
    private boolean debugAllowUserHeader = false;

    @PostConstruct
    public void warnIfDebugEnabled() {
        if (debugAllowUserHeader) {
            log.warn("================ 安全告警 ================");
            log.warn("hm.auth.debug-allow-user-header = true");
            log.warn("网关将信任客户端自带的 X-User-Id，任何人都能伪造身份越权访问。");
            log.warn("仅限本机调试使用，生产环境必须置为 false 或删除该配置。");
            log.warn("=========================================");
        }
    }
}
