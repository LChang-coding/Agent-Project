package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
/** 服务端保存的刷新令牌凭证。 */
public class RefreshTokenEntity {

    /** 令牌所属租户。 */
    private String tenantId;
    /** 令牌所属用户。 */
    private String userId;
    /** 刷新令牌单向哈希，不保存明文。 */
    private String tokenHash;
    /** 服务端凭证过期时间。 */
    private LocalDateTime expireTime;
    /** active 或 disabled。 */
    private String status;
}
