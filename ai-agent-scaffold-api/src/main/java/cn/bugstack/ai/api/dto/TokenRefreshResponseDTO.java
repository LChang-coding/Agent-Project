package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class TokenRefreshResponseDTO {

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
