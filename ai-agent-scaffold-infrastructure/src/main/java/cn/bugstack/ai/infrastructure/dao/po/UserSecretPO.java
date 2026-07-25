package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户凭证的摘要、盐和生命周期；不保存可直接使用的明文。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserSecretPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 用户业务ID
     */
    private String userId;

    /**
     * 凭证类型：password/api_key/oauth
     */
    private String secretType;

    /**
     * 凭证密文或哈希值
     */
    private String secretValueHash;

    /**
     * 密码盐值
     */
    private String salt;

    /**
     * 凭证过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 凭证状态：active/disabled/expired
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
