package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/** 新签发或刷新后的双令牌结果。 */
public class TokenResultEntity {

    /** 短期访问令牌。 */
    private String token;
    /** 新的长期刷新令牌。 */
    private String refreshToken;
    /** 固定为 Bearer。 */
    private String tokenType;
    /** 访问令牌有效秒数。 */
    private Long expiresIn;
    /** 刷新令牌有效秒数。 */
    private Long refreshExpiresIn;
    /** 令牌声明中的租户。 */
    private String tenantId;
    /** 令牌声明中的用户。 */
    private String userId;
    /** 令牌声明中的登录名。 */
    private String username;
    /** 令牌声明中的租户角色。 */
    private String roleCode;
}
