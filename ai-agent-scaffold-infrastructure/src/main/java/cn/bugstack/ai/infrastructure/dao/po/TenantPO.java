package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 企业租户的稳定身份和启停状态。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TenantPO extends BasePO {

    /**
     * 租户业务ID
     */
    private String tenantId;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 租户编码
     */
    private String tenantCode;

    /**
     * 租户状态：active/disabled
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
