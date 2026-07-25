package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
/** 认证用账号、租户成员关系和密码状态快照。 */
public class AuthUserEntity {

    /** 当前租户。 */
    private String tenantId;
    /** 平台用户标识。 */
    private String userId;
    /** 唯一登录名。 */
    private String username;
    /** 展示昵称。 */
    private String nickname;
    /** 联系邮箱。 */
    private String email;
    /** 联系电话。 */
    private String phone;
    /** 头像地址。 */
    private String avatar;
    /** 平台账号状态。 */
    private String userStatus;
    /** 当前租户角色。 */
    private String roleCode;
    /** 租户成员关系状态。 */
    private String tenantUserStatus;
    /** 单向密码哈希。 */
    private String passwordHash;
    /** 密码凭证过期时间。 */
    private LocalDateTime passwordExpireTime;
    /** 密码凭证状态。 */
    private String passwordStatus;
}
