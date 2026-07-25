package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
/** 创建租户与首个 owner 的注册命令。 */
public class RegisterCommandEntity {

    /** 新租户展示名。 */
    private String tenantName;
    /** 首个 owner 的唯一登录名。 */
    private String username;
    /** 待哈希保存的明文密码。 */
    private String password;
    /** owner 展示昵称。 */
    private String nickname;
    /** owner 联系邮箱。 */
    private String email;
    /** owner 联系电话。 */
    private String phone;
}
