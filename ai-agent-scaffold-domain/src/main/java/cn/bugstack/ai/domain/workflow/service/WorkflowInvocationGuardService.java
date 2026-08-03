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
    private final IChatRunRepository runRepository;
    private final IWorkflowInvocationRepository invocationRepository;

    public WorkflowInvocationGuardService(IChatRunRepository runRepository,
                                          IWorkflowInvocationRepository invocationRepository) {
        this.runRepository = runRepository;
        this.invocationRepository = invocationRepository;
    }

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

    public int success(String tenantId, String invocationId, String downstreamRequestId) {
        return invocationRepository.finish(tenantId, invocationId, "SUCCEEDED", downstreamRequestId);
    }

    public int failed(String tenantId, String invocationId, String downstreamRequestId) {
        return invocationRepository.finish(tenantId, invocationId, "FAILED", downstreamRequestId);
    }

    public WorkflowInvocationEntity modelInvocation(ChatRunEntity run, String nodeExecutionId) {
        return WorkflowInvocationEntity.builder().tenantId(run.getTenantId()).runId(run.getRunId())
                .invocationId("wfi_" + java.util.UUID.randomUUID())
                .idempotencyKey(run.getRunId() + ":" + nodeExecutionId + ":MODEL:1")
                .nodeExecutionId(nodeExecutionId).invocationType("MODEL").replayClass("IDEMPOTENT")
                .status("RUNNING").traceId(run.getTraceId()).startedAt(LocalDateTime.now()).build();
    }
}
