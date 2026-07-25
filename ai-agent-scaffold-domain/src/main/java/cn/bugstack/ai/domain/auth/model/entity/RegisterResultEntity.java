package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/** 注册成功后返回的新租户 owner 身份。 */
public class RegisterResultEntity {

    /** 新租户标识。 */
    private String tenantId;
    /** 新用户标识。 */
    private String userId;
    /** 唯一登录名。 */
    private String username;
    /** 固定为 owner。 */
    private String roleCode;
}
