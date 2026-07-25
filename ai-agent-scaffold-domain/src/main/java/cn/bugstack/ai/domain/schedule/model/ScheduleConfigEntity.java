package cn.bugstack.ai.domain.schedule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 定时任务配置实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleConfigEntity {

    /** 配置所属租户。 */
    private String tenantId;
    /** 创建并管理配置的用户。 */
    private String ownerUserId;
    /** 触发时恢复到线程上下文的执行用户。 */
    private String runAsUserId;
    /** 触发时恢复到线程上下文的执行角色。 */
    private String runAsRoleCode;
    /** 配置的稳定业务标识。 */
    private String configId;
    /** 被定时唤醒的 Agent。 */
    private String agentId;
    /** 展示用 Agent 名称，不参与调度判定。 */
    private String agentName;
    /** 处理器路由键，当前固定为 agent_prompt。 */
    private String taskType;
    /** 经过白名单校验的任务 JSON 载荷。 */
    private String taskPayload;
    /** Spring 六段式 Cron 表达式。 */
    private String cronExpr;
    /** Cron 解释所使用的 IANA 时区。 */
    private String timezone;
    /** 用户是否启用该配置。 */
    private boolean enabled;
    /** 配置生命周期状态。 */
    private String status;
    /** 错过计划时间后的补偿策略。 */
    private String misfirePolicy;
    /** 单个触发点允许的最大重试次数。 */
    private int maxRetries;
    /** 影响运行语义字段的规范化摘要。 */
    private String configHash;
    /** 摘要变化时递增的配置版本。 */
    private long configVersion;
    /** 最近一次成功生成运行态的时间。 */
    private LocalDateTime lastReconciledAt;
    /** 配置创建时间。 */
    private LocalDateTime createTime;
    /** 乐观并发控制使用的最后更新时间。 */
    private LocalDateTime updateTime;
}
