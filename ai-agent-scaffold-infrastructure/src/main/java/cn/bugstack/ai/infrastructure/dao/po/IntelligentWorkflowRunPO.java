package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 智能工作流运行状态行，仅投影事件序号分配所需字段。 */
@Data
public class IntelligentWorkflowRunPO {
    /** 数据库自增主键。 */
    private Long id;
    /** 运行所属租户。 */
    private String tenantId;
    /** 发起运行的用户。 */
    private String userId;
    /** 对外稳定的运行标识。 */
    private String runId;
    /** 运行根链路标识，事件写入必须保持一致。 */
    private String traceId;
    /** 当前运行状态。 */
    private String status;
    /** 本次运行采用的工作流。 */
    private String workflowId;
    /** 本次运行冻结的工作流版本。 */
    private Integer workflowVersion;
    /** 用于检测定义漂移的内容摘要。 */
    private String definitionHash;
    /** 当前正在执行或等待执行的节点。 */
    private String currentNodeId;
    /** 下一条事件应使用的序号。 */
    private Long nextSequence;
    /** 已消耗的节点执行步数。 */
    private Integer executedSteps;
    /** 已消耗的模型 Token 数。 */
    private Long usedTokens;
    /** 本次运行允许的最大节点步数。 */
    private Integer maxSteps;
    /** 本次运行允许使用的 Token 总量。 */
    private Long tokenBudget;
    /** 节点之间传递的运行变量快照。 */
    private String variablesJson;
    /** 状态更新使用的乐观锁版本。 */
    private Long revision;
    /** 运行开始时间。 */
    private LocalDateTime startedAt;
    /** 运行进入终态的时间。 */
    private LocalDateTime finishedAt;
}
