package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResultEntity {

    private String token;

    private String tokenType;

    private Long expiresIn;

    private String tenantId;

    private String userId;

    private String username;

    private String roleCode;
}
