package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流外部调用记录；登记成功后才允许执行对应的网络调用。 */
@Data
@Builder
public class WorkflowInvocationEntity {

    /** 调用所属租户。 */
    private String tenantId;

    /** 调用所属工作流运行。 */
    private String runId;

    /** 外部调用唯一标识。 */
    private String invocationId;

    /** 防止同一节点动作重复执行的运行级幂等键。 */
    private String idempotencyKey;

    /** 发起调用的逻辑节点执行。 */
    private String nodeExecutionId;

    /** 调用类型，例如 MODEL 或 TOOL。 */
    private String invocationType;

    /** 重放分类，说明重复请求是否可以直接复用既有结果。 */
    private String replayClass;

    /** 调用状态，例如 RUNNING、SUCCEEDED 或 FAILED。 */
    private String status;

    /** 外部服务返回的请求标识，用于核对实际调用。 */
    private String downstreamRequestId;

    /** 与根工作流运行一致的跟踪标识。 */
    private String traceId;

    /** 调用获准并开始执行的时间。 */
    private LocalDateTime startedAt;

    /** 调用成功或失败的时间。 */
    private LocalDateTime finishedAt;
}
