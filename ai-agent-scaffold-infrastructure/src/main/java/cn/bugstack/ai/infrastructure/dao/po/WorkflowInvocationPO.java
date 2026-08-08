package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** workflow_invocation 表持久化对象。 */
@Data
public class WorkflowInvocationPO {
    /** 调用所属租户。 */
    private String tenantId;
    /** 调用所属工作流运行。 */
    private String runId;
    /** 一次调用的稳定标识。 */
    private String invocationId;
    /** 阻止同一业务调用重复执行的唯一键。 */
    private String idempotencyKey;
    /** 发起调用的节点执行标识。 */
    private String nodeExecutionId;
    /** 调用的能力类别。 */
    private String invocationType;
    /** 运行恢复时采用的重放策略。 */
    private String replayClass;
    /** 调用当前状态。 */
    private String status;
    /** 外部服务返回的请求标识。 */
    private String downstreamRequestId;
    /** 调用所在链路的标识。 */
    private String traceId;
    /** 调用开始时间。 */
    private LocalDateTime startedAt;
}
