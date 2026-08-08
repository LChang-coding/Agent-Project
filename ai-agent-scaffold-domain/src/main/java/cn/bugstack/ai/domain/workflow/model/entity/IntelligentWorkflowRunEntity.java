package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** chat_run 的智能工作流状态扩展；根取消和会话归属仍由 chat_run 裁决。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntelligentWorkflowRunEntity {

    /** 扩展运行所属租户。 */
    private String tenantId;

    /** 扩展运行所属用户。 */
    private String userId;

    /** 与通用 chat_run 共享的运行标识。 */
    private String runId;

    /** 本次运行使用的工作流定义。 */
    private String workflowId;

    /** 本次运行固定使用的发布版本。 */
    private Integer workflowVersion;

    /** 发布定义摘要，用于恢复时确认定义未发生变化。 */
    private String definitionHash;

    /** 与通用运行一致的跟踪标识。 */
    private String traceId;

    /** 智能调度状态；取消和会话归属仍以通用运行为准。 */
    private String status;

    /** 下一次调度准备执行的节点。 */
    private String currentNodeId;

    /** 预留的运行内事件序号；事件仓储分配完成后递增。 */
    private Long nextSequence;

    /** 已完成调度的节点总次数，用于最大步数限制。 */
    private Integer executedSteps;

    /** 已计入本次运行预算的 Token 总数。 */
    private Long usedTokens;

    /** 本次运行允许执行的最大节点步数。 */
    private Integer maxSteps;

    /** 本次运行允许使用的 Token 总预算。 */
    private Long tokenBudget;

    /** 保存节点访问次数和其他恢复变量的 JSON。 */
    private String variablesJson;

    /** 乐观锁修订号，更新运行状态时必须提供预期值。 */
    private Long revision;

    /** 智能工作流开始执行的时间。 */
    private LocalDateTime startedAt;

    /** 智能工作流进入终态的时间。 */
    private LocalDateTime finishedAt;
}
