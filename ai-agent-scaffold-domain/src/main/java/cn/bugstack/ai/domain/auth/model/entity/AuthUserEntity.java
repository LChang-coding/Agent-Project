package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuthUserEntity {

    private String tenantId;

    private String userId;

    private String username;

    private String nickname;

    private String userStatus;

    private String roleCode;

    private String tenantUserStatus;

    private String passwordHash;

    private LocalDateTime passwordExpireTime;

    private String passwordStatus;
}
