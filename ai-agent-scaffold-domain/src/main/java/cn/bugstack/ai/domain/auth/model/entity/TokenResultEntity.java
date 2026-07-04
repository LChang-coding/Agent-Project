package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenResultEntity {

    private String token;

    private String refreshToken;

    private String tokenType;

    private Long expiresIn;

    private Long refreshExpiresIn;

    private String tenantId;

    private String userId;

    private String username;

    private String roleCode;
}
