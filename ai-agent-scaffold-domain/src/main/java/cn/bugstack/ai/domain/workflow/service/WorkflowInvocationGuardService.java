package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowInvocationRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 在 chat_run 行锁内登记调用，线性化取消提交与新外部调用。 */
@Service
public class WorkflowInvocationGuardService {
    /** 在登记外部调用前锁定并核验通用运行状态。 */
    private final IChatRunRepository runRepository;
    /** 持久化调用幂等键并推进调用状态。 */
    private final IWorkflowInvocationRepository invocationRepository;

    /**
     * 创建工作流外部调用登记服务。
     *
     * @param runRepository 用于锁定并核验运行状态的仓储
     * @param invocationRepository 保存调用记录和幂等键的仓储
     */
    public WorkflowInvocationGuardService(IChatRunRepository runRepository,
                                          IWorkflowInvocationRepository invocationRepository) {
        this.runRepository = runRepository;
        this.invocationRepository = invocationRepository;
    }

    /**
     * 在运行行锁保护下登记一次尚未开始的外部调用。
     *
     * @param invocation 包含运行、节点、幂等键和跟踪标识的调用记录
     * @param userId 当前运行所属的可信用户
     * @return true 表示首次登记，false 表示相同幂等键已经存在
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean register(WorkflowInvocationEntity invocation, String userId) {
        ChatRunEntity run = runRepository.lock(invocation.getTenantId(), userId, invocation.getRunId());
        if (run == null) throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        if (!run.getStatus().executable() || run.getCancelRequestedAt() != null) {
            throw new AppException("RUN_NOT_EXECUTABLE", "运行已取消或结束，禁止登记新调用");
        }
        if (!run.getTraceId().equals(invocation.getTraceId())) {
            throw new AppException("WORKFLOW_TRACE_MISMATCH", "调用 traceId 与运行根链路不一致");
        }
        return invocationRepository.insertIgnore(invocation) == 1;
    }

    /**
     * 将调用记录更新为成功。
     *
     * @param tenantId 调用记录所属租户
     * @param invocationId 待完成的调用标识
     * @param downstreamRequestId 外部服务返回的请求标识，便于核对实际调用
     * @return 实际更新的记录数；零表示调用不存在或已经进入终态
     */
    public int success(String tenantId, String invocationId, String downstreamRequestId) {
        return invocationRepository.finish(tenantId, invocationId, "SUCCEEDED", downstreamRequestId);
    }

    /**
     * 将调用记录更新为失败。
     *
     * @param tenantId 调用记录所属租户
     * @param invocationId 待完成的调用标识
     * @param downstreamRequestId 外部服务返回的请求标识；请求未发出时可以为空
     * @return 实际更新的记录数；零表示调用不存在或已经进入终态
     */
    public int failed(String tenantId, String invocationId, String downstreamRequestId) {
        return invocationRepository.finish(tenantId, invocationId, "FAILED", downstreamRequestId);
    }

    /**
     * 为当前节点构造一次模型调用记录。
     *
     * @param run 已确认可执行的工作流运行
     * @param nodeExecutionId 本次逻辑节点执行标识
     * @return 带有运行级幂等键和跟踪标识的待登记调用
     */
    public WorkflowInvocationEntity modelInvocation(ChatRunEntity run, String nodeExecutionId) {
        return modelInvocation(run, nodeExecutionId, 1);
    }

    /** 为节点的某次 Agent 尝试创建独立调用记录，便于区分首次调用和后续重试。 */
    public WorkflowInvocationEntity modelInvocation(ChatRunEntity run, String nodeExecutionId, int attempt) {
        return WorkflowInvocationEntity.builder().tenantId(run.getTenantId()).runId(run.getRunId())
                .invocationId("wfi_" + java.util.UUID.randomUUID())
                .idempotencyKey(run.getRunId() + ":" + nodeExecutionId + ":MODEL:" + Math.max(1, attempt))
                .nodeExecutionId(nodeExecutionId).invocationType("MODEL").replayClass("IDEMPOTENT")
                .status("RUNNING").traceId(run.getTraceId()).startedAt(LocalDateTime.now()).build();
    }
}
