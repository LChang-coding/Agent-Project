package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.SessionOrchestrationSnapshotEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把任务表与审批表投影成一个会话级、可恢复的 Multi-Agent 运行视图。 */
@Service
public class SessionOrchestrationQueryService {
    private final ISubagentTaskRepository taskRepository;
    private final IToolApprovalRepository approvalRepository;

    public SessionOrchestrationQueryService(ISubagentTaskRepository taskRepository,
                                            IToolApprovalRepository approvalRepository) {
        this.taskRepository = taskRepository;
        this.approvalRepository = approvalRepository;
    }

    public SessionOrchestrationSnapshotEntity query(String tenantId, String userId, String sessionId) {
        requireText(tenantId); requireText(userId); requireText(sessionId);
        List<SubagentTaskEntity> tasks = taskRepository.queryBySession(tenantId, userId, sessionId, 100);
        List<ToolApprovalRequestEntity> approvals = approvalRepository
                .queryPendingBySession(tenantId, userId, sessionId, 100);

        Map<String, List<SubagentTaskEntity>> groups = new LinkedHashMap<>();
        tasks.stream().sorted(Comparator.comparing(SubagentTaskEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(task -> groups.computeIfAbsent(task.getParentRunId(), ignored -> new ArrayList<>()).add(task));

        List<SessionOrchestrationSnapshotEntity.Run> runs = groups.entrySet().stream().map(entry -> run(entry.getKey(), entry.getValue(), approvals)).toList();
        String approvalRunId = approvals.isEmpty() ? null : approvals.get(0).getParentRunId();
        String currentRunId = approvalRunId != null ? approvalRunId : runs.stream()
                .filter(value -> activePhase(value.getPhase())).map(SessionOrchestrationSnapshotEntity.Run::getParentRunId)
                .findFirst().orElse(runs.isEmpty() ? null : runs.get(0).getParentRunId());
        String phase = currentRunId == null ? "IDLE" : phaseForRun(currentRunId, tasks, approvals);
        boolean active = activePhase(phase);
        String versionSource = tasks.stream().map(value -> value.getTaskId() + ':' + value.getStatus() + ':'
                        + safe(value.getCallbackStatus()) + ':' + safe(value.getChildSessionId()) + ':' + safe(value.getCompletedAt()))
                .reduce("", (a, b) -> a + '|' + b)
                + approvals.stream().map(value -> value.getApprovalId() + ':' + value.getRevision())
                .reduce("", (a, b) -> a + '|' + b);
        return SessionOrchestrationSnapshotEntity.builder().sessionId(sessionId)
                .version(Integer.toUnsignedString(versionSource.hashCode(), 36)).active(active).inputLocked(active)
                .phase(phase).currentRunId(currentRunId).runs(runs)
                .approvals(approvals.stream().map(this::approval).toList()).build();
    }

    public void assertAcceptsUserMessage(String tenantId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        SessionOrchestrationSnapshotEntity snapshot = query(tenantId, userId, sessionId);
        if (snapshot.isInputLocked()) {
            throw new AppException("SESSION_ORCHESTRATION_ACTIVE", "当前会话仍在执行 Multi-Agent 任务，请等待汇总完成或取消任务");
        }
    }

    public SessionOrchestrationSnapshotEntity.Task queryTask(String tenantId, String userId, String sessionId,
                                                              String parentRunId, String taskId) {
        requireText(tenantId); requireText(userId); requireText(sessionId); requireText(parentRunId); requireText(taskId);
        SubagentTaskEntity value = taskRepository.queryByIds(tenantId, parentRunId, List.of(taskId)).stream()
                .filter(task -> userId.equals(task.getUserId()) && sessionId.equals(task.getParentSessionId()))
                .findFirst().orElseThrow(() -> new AppException("SUBAGENT_TASK_NOT_FOUND", "子 Agent 任务不存在"));
        return task(value, true);
    }

    private SessionOrchestrationSnapshotEntity.Run run(String parentRunId, List<SubagentTaskEntity> tasks,
                                                        List<ToolApprovalRequestEntity> approvals) {
        LocalDateTime createdAt = tasks.stream().map(SubagentTaskEntity::getCreatedAt).filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime completedAt = tasks.stream().map(SubagentTaskEntity::getCompletedAt).filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
        return SessionOrchestrationSnapshotEntity.Run.builder().parentRunId(parentRunId)
                .parentAgentId(tasks.isEmpty() ? null : tasks.get(0).getParentAgentId())
                .phase(phaseForRun(parentRunId, tasks, approvals)).createdAt(createdAt).completedAt(completedAt)
                .tasks(tasks.stream().map(value -> task(value, false)).toList()).build();
    }

    private String phaseForRun(String runId, List<SubagentTaskEntity> tasks, List<ToolApprovalRequestEntity> approvals) {
        if (approvals.stream().anyMatch(value -> runId.equals(value.getParentRunId()))) return "WAITING_APPROVAL";
        List<SubagentTaskEntity> scoped = tasks.stream().filter(value -> runId.equals(value.getParentRunId())).toList();
        if (scoped.stream().anyMatch(value -> value.getStatus() == SubagentTaskStatus.READY || value.getStatus() == SubagentTaskStatus.RUNNING)) return "EXECUTING";
        if (!scoped.isEmpty() && scoped.stream().allMatch(value -> value.getStatus() == SubagentTaskStatus.CANCELLED)) return "CANCELLED";
        if (scoped.stream().anyMatch(value -> value.getStatus().terminal() && !"DELIVERED".equals(value.getCallbackStatus()))) return "SUMMARIZING";
        if (scoped.stream().anyMatch(value -> value.getStatus() == SubagentTaskStatus.FAILED || value.getErrorCode() != null)) return "COMPLETED_WITH_ERRORS";
        return scoped.isEmpty() ? "IDLE" : "COMPLETED";
    }

    private boolean activePhase(String phase) {
        return "WAITING_APPROVAL".equals(phase) || "EXECUTING".equals(phase) || "SUMMARIZING".equals(phase);
    }

    private SessionOrchestrationSnapshotEntity.Task task(SubagentTaskEntity value, boolean includeFullContext) {
        return SessionOrchestrationSnapshotEntity.Task.builder().taskId(value.getTaskId())
                .childAgentId(value.getChildAgentId()).childSessionId(value.getChildSessionId())
                .instruction(value.getInstruction()).traceId(value.getTraceId()).status(value.getStatus().name())
                .callbackStatus(value.getCallbackStatus()).attempt(value.getAttempt()).resultSummary(value.getResultSummary())
                .fullContext(includeFullContext ? value.getFullContext() : null).errorCode(value.getErrorCode()).createdAt(value.getCreatedAt())
                .completedAt(value.getCompletedAt()).build();
    }

    private SessionOrchestrationSnapshotEntity.Approval approval(ToolApprovalRequestEntity value) {
        return SessionOrchestrationSnapshotEntity.Approval.builder().approvalId(value.getApprovalId())
                .parentRunId(value.getParentRunId()).parentAgentId(value.getParentAgentId()).toolCode(value.getToolCode())
                .expiresAt(String.valueOf(value.getExpiresAt())).revision(value.getRevision()).build();
    }

    private void requireText(String value) { if (value == null || value.isBlank()) throw new AppException("ILLEGAL_PARAMETER", "会话编排查询参数不能为空"); }
    private String safe(Object value) { return value == null ? "" : String.valueOf(value); }
}
