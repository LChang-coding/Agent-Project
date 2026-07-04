package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class UserProfileResponseDTO {

    private String tenantId;

    private String userId;

    private String username;

    private String nickname;

    private String email;

    private String phone;

    private String avatar;

    private String roleCode;
}
