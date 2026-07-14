package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentScheduleConfigPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 配置拥有者用户ID
     */
    private String ownerUserId;

    /**
     * 任务执行身份用户ID，用于上下文、权限和用量归属
     */
    private String runAsUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * 调度配置业务ID
     */
    private String configId;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent 名称
     */
    private String agentName;

    private String taskType;

    private String taskPayload;

    private String runAsRoleCode;

    /**
     * Cron 表达式
     */
    private String cronExpr;

    /**
     * 时区
     */
    private String timezone;

    /**
     * 是否启用，0否1是
     */
    private Integer enabled;

    /**
     * 配置状态：active/disabled/archived
     */
    private String status;

    private String misfirePolicy;

    private Integer maxRetries;

    private String configHash;

    private Long configVersion;

    private LocalDateTime lastReconciledAt;

    /**
     * 扩展信息
     */
    private String metadata;
}
