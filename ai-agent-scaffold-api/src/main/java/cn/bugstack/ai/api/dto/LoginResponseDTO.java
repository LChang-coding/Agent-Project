package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class LoginResponseDTO {

    private String token;

    private String tokenType;

    private Long expiresIn;

    private String tenantId;

    private String userId;

    private String username;

    private String roleCode;
}
