package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class RegisterResponseDTO {

    private String tenantId;

    private String userId;

    private String username;

    private String roleCode;
}
