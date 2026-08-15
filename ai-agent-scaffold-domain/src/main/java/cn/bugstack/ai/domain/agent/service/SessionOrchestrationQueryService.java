package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.SessionOrchestrationSnapshotEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
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
    private final IParentResumeRepository parentResumeRepository;
    private final IChatRunRepository runRepository;

    public SessionOrchestrationQueryService(ISubagentTaskRepository taskRepository,
                                            IToolApprovalRepository approvalRepository,
                                            IParentResumeRepository parentResumeRepository,
                                            IChatRunRepository runRepository) {
        this.taskRepository = taskRepository;
        this.approvalRepository = approvalRepository;
        this.parentResumeRepository = parentResumeRepository;
        this.runRepository = runRepository;
    }

    public SessionOrchestrationSnapshotEntity query(String tenantId, String userId, String sessionId) {
        requireText(tenantId); requireText(userId); requireText(sessionId);
        List<SubagentTaskEntity> tasks = taskRepository.queryBySession(tenantId, userId, sessionId, 100);
        List<ToolApprovalRequestEntity> approvals = approvalRepository
                .queryPendingBySession(tenantId, userId, sessionId, 100);
        List<ChatRunEntity> executableRuns = runRepository.queryExecutableBySession(tenantId, userId, sessionId);

        Map<String, List<SubagentTaskEntity>> groups = new LinkedHashMap<>();
        tasks.stream().sorted(Comparator.comparing(SubagentTaskEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(task -> groups.computeIfAbsent(task.getParentRunId(), ignored -> new ArrayList<>()).add(task));

        Map<String, String> resumeStatuses = new LinkedHashMap<>();
        groups.keySet().forEach(runId -> resumeStatuses.put(runId, parentResumeRepository.queryStatus(tenantId, runId)));
        List<SessionOrchestrationSnapshotEntity.Run> runs = groups.entrySet().stream()
                .map(entry -> run(entry.getKey(), entry.getValue(), approvals, resumeStatuses.get(entry.getKey()))).toList();
        String approvalRunId = approvals.isEmpty() ? null : approvals.get(0).getParentRunId();
        String executableRunId = executableRuns.isEmpty() ? null : executableRuns.get(0).getRunId();
        String currentRunId = approvalRunId != null ? approvalRunId : runs.stream()
                .filter(value -> activePhase(value.getPhase())).map(SessionOrchestrationSnapshotEntity.Run::getParentRunId)
                .findFirst().orElse(executableRunId != null ? executableRunId : runs.isEmpty() ? null : runs.get(0).getParentRunId());
        String phase = currentRunId == null ? "IDLE"
                : groups.containsKey(currentRunId)
                ? phaseForRun(currentRunId, tasks, approvals, resumeStatuses.get(currentRunId))
                : "EXECUTING";
        boolean active = activePhase(phase);
        String versionSource = tasks.stream().map(value -> value.getTaskId() + ':' + value.getStatus() + ':'
                        + safe(value.getCallbackStatus()) + ':' + safe(value.getChildSessionId()) + ':' + safe(value.getCompletedAt()))
                .reduce("", (a, b) -> a + '|' + b)
                + approvals.stream().map(value -> value.getApprovalId() + ':' + value.getRevision())
                .reduce("", (a, b) -> a + '|' + b)
                + resumeStatuses.entrySet().stream().map(value -> value.getKey() + ':' + safe(value.getValue()))
                .reduce("", (a, b) -> a + '|' + b)
                + executableRuns.stream().map(value -> value.getRunId() + ':' + value.getStatus() + ':' + value.getVersion())
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

    /** WAIT_ALL 活跃期间拒绝删除等会话级变更，避免父侧屏障失去承载会话。 */
    public void assertAcceptsSessionMutation(String tenantId, String userId, String sessionId) {
        assertAcceptsUserMessage(tenantId, userId, sessionId);
    }

    /** WAIT_ALL 已建立后，父运行不能再被取消或引导，否则会破坏父侧屏障。 */
    public void assertAcceptsRunMutation(String tenantId, String parentRunId) {
        requireText(tenantId); requireText(parentRunId);
        if (parentResumeRepository.isAwaitingSummary(tenantId, parentRunId)) {
            throw new AppException("SESSION_ORCHESTRATION_ACTIVE", "当前运行正在等待 Multi-Agent 统一汇总，不能取消或引导");
        }
    }

    /** 最终消息事务内锁定恢复账本，确保当前 Worker 仍持有未过期 fencing 租约。 */
    public void assertOwnsResumeLease(String tenantId) {
        if (!AgentOrchestrationContextHolder.isSummaryOnly()) return;
        String parentRunId = AgentOrchestrationContextHolder.getRootRunId();
        String owner = AgentOrchestrationContextHolder.getResumeLeaseOwner();
        Long fencingToken = AgentOrchestrationContextHolder.getResumeFencingToken();
        if (parentRunId == null || owner == null || fencingToken == null
                || !parentResumeRepository.lockOwnedLease(
                tenantId, parentRunId, owner, fencingToken, LocalDateTime.now())) {
            throw new AppException("PARENT_RESUME_LEASE_LOST", "主 Agent 恢复租约已失效，本次结果不得提交");
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
                                                        List<ToolApprovalRequestEntity> approvals,
                                                        String resumeStatus) {
        LocalDateTime createdAt = tasks.stream().map(SubagentTaskEntity::getCreatedAt).filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime completedAt = tasks.stream().map(SubagentTaskEntity::getCompletedAt).filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
        return SessionOrchestrationSnapshotEntity.Run.builder().parentRunId(parentRunId)
                .parentAgentId(tasks.isEmpty() ? null : tasks.get(0).getParentAgentId())
                .phase(phaseForRun(parentRunId, tasks, approvals, resumeStatus)).createdAt(createdAt).completedAt(completedAt)
                .tasks(tasks.stream().map(value -> task(value, false)).toList()).build();
    }

    private String phaseForRun(String runId, List<SubagentTaskEntity> tasks,
                               List<ToolApprovalRequestEntity> approvals, String resumeStatus) {
        if (approvals.stream().anyMatch(value -> runId.equals(value.getParentRunId()))) return "WAITING_APPROVAL";
        List<SubagentTaskEntity> scoped = tasks.stream().filter(value -> runId.equals(value.getParentRunId())).toList();
        if ("WAITING".equals(resumeStatus)) {
            return scoped.stream().anyMatch(value -> value.getStatus() == SubagentTaskStatus.READY
                    || value.getStatus() == SubagentTaskStatus.RUNNING) ? "EXECUTING" : "SUMMARIZING";
        }
        if ("PENDING".equals(resumeStatus) || "RUNNING".equals(resumeStatus)
                || "RETRYING".equals(resumeStatus)) return "SUMMARIZING";
        if (!"COMPLETED".equals(resumeStatus)
                && scoped.stream().anyMatch(value -> value.getStatus() == SubagentTaskStatus.READY
                || value.getStatus() == SubagentTaskStatus.RUNNING)) return "EXECUTING";
        if (!scoped.isEmpty() && scoped.stream().allMatch(value -> value.getStatus() == SubagentTaskStatus.CANCELLED)) return "CANCELLED";
        if (resumeStatus == null
                && scoped.stream().anyMatch(value -> value.getStatus().terminal()
                && !"DELIVERED".equals(value.getCallbackStatus()))) return "SUMMARIZING";
        if (scoped.stream().anyMatch(value -> value.getStatus() == SubagentTaskStatus.FAILED || value.getErrorCode() != null)) return "COMPLETED_WITH_ERRORS";
        return scoped.isEmpty() ? "IDLE" : "COMPLETED";
    }

    private boolean activePhase(String phase) {
        return "WAITING_APPROVAL".equals(phase) || "EXECUTING".equals(phase) || "SUMMARIZING".equals(phase);
    }

    private SessionOrchestrationSnapshotEntity.Task task(SubagentTaskEntity value, boolean includeFullContext) {
        ChatRunEntity childRun = value.getChildSessionId() == null ? null
                : runRepository.queryLatestBySession(value.getTenantId(), value.getUserId(), value.getChildSessionId());
        return SessionOrchestrationSnapshotEntity.Task.builder().taskId(value.getTaskId())
                .childAgentId(value.getChildAgentId()).childSessionId(value.getChildSessionId())
                .childRunId(childRun == null ? null : childRun.getRunId())
                .childRunTraceId(childRun == null ? null : childRun.getTraceId())
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
