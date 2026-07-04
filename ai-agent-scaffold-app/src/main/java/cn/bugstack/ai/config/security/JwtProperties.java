package cn.bugstack.ai.config.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    private String issuer = "ai-agent-scaffold";

    private String secret = "dev-only-change-me-ai-agent-scaffold";

    private Long expireSeconds = 7200L;
}
