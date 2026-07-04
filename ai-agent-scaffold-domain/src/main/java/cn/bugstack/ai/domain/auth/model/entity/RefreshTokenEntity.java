package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RefreshTokenEntity {

    private String tenantId;

    private String userId;

    private String tokenHash;

    private LocalDateTime expireTime;

    private String status;
}
