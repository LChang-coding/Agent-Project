package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserProfileEntity {

    private String tenantId;

    private String userId;

    private String username;

    private String nickname;

    private String email;

    private String phone;

    private String avatar;

    private String roleCode;
}
