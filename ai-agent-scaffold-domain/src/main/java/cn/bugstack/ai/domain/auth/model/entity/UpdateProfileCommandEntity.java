package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
/** 当前用户可编辑资料命令。 */
public class UpdateProfileCommandEntity {

    /** 来自认证上下文的用户。 */
    private String userId;
    /** 新昵称；null 表示保持原值。 */
    private String nickname;
    /** 新邮箱；null 表示保持原值。 */
    private String email;
    /** 新电话；null 表示保持原值。 */
    private String phone;
    /** 新头像；null 表示保持原值。 */
    private String avatar;
}
