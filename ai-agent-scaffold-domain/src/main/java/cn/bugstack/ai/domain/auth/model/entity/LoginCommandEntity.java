package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
/** 用户名密码登录命令。 */
public class LoginCommandEntity {

    /** 登录名。 */
    private String username;
    /** 明文密码，只在认证调用期间存在。 */
    private String password;
}
