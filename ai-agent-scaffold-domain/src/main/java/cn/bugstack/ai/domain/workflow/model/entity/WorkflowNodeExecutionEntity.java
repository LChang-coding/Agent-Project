package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次逻辑节点执行的可追溯快照。 */
@Data
@Builder
public class WorkflowNodeExecutionEntity {

    /** 节点执行所属租户。 */
    private String tenantId;

    /** 节点执行所属的工作流运行。 */
    private String runId;

    /** 逻辑节点执行标识；节点重复访问时每次生成新值。 */
    private String nodeExecutionId;

    /** 工作流定义中的节点标识。 */
    private String nodeId;

    /** 当前运行中的节点执行顺序。 */
    private Integer executionIndex;

    /** 同一逻辑节点执行的尝试次数。 */
    private Integer attempt;

    /** 节点执行状态，例如 RUNNING、SUCCEEDED、FAILED 或 CANCELLED。 */
    private String status;

    /** 可直接展示给用户的节点输出文本。 */
    private String displayOutput;

    /** 供后续节点和恢复流程读取的结构化输出。 */
    private String outputJson;

    /** 本节点模型请求使用的输入 Token 数。 */
    private Long promptTokens;

    /** 本节点模型响应使用的输出 Token 数。 */
    private Long candidateTokens;

    /** 本节点模型调用的 Token 总数。 */
    private Long totalTokens;

    /** 节点失败时的稳定错误码。 */
    private String errorCode;

    /** 可安全展示和审计的错误说明。 */
    private String errorMessage;

    /** 与根工作流运行一致的跟踪标识。 */
    private String traceId;

    /** 节点开始执行的时间。 */
    private LocalDateTime startedAt;

    /** 节点完成、失败或取消的时间。 */
    private LocalDateTime finishedAt;
}
