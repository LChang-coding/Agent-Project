package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
/** 已登录用户修改密码命令。 */
public class ChangePasswordCommandEntity {

    /** 来自认证上下文的用户。 */
    private String userId;
    /** 用于二次验证的旧密码。 */
    private String oldPassword;
    /** 待哈希保存的新密码。 */
    private String newPassword;
}
