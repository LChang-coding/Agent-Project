package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/** 登录成功后的双令牌与可信身份。 */
public class LoginResultEntity {

    /** 短期访问令牌。 */
    private String token;
    /** 可轮换的长期刷新令牌。 */
    private String refreshToken;
    /** 固定为 Bearer。 */
    private String tokenType;
    /** 访问令牌剩余秒数。 */
    private Long expiresIn;
    /** 刷新令牌剩余秒数。 */
    private Long refreshExpiresIn;
    /** 登录租户。 */
    private String tenantId;
    /** 登录用户。 */
    private String userId;
    /** 登录名。 */
    private String username;
    /** 当前租户角色。 */
    private String roleCode;
}
