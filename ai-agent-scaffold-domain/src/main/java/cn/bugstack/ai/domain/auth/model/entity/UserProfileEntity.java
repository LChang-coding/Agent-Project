package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/** 对当前用户公开的资料与租户角色。 */
public class UserProfileEntity {

    /** 当前租户。 */
    private String tenantId;
    /** 当前用户。 */
    private String userId;
    /** 不可由资料更新修改的登录名。 */
    private String username;
    /** 展示昵称。 */
    private String nickname;
    /** 联系邮箱。 */
    private String email;
    /** 联系电话。 */
    private String phone;
    /** 头像地址。 */
    private String avatar;
    /** 当前租户角色。 */
    private String roleCode;
}
