package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户声明的 Cron 配置及协调器同步游标。 */
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

    /** 执行目标类型，如 Agent 或 Workflow。 */
    private String taskType;

    /** 触发时传给目标的序列化业务参数。 */
    private String taskPayload;

    /** 定时执行采用的可信角色。 */
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

    /** 错过计划时刻后的补偿策略。 */
    private String misfirePolicy;

    /** 单次计划发生允许的最大重试数。 */
    private Integer maxRetries;

    /** 参与运行时落库字段的稳定摘要。 */
    private String configHash;

    /** 每次有效配置变更递增的版本。 */
    private Long configVersion;

    /** 长周期协调器最近成功同步时间。 */
    private LocalDateTime lastReconciledAt;

    /**
     * 扩展信息
     */
    private String metadata;
}
