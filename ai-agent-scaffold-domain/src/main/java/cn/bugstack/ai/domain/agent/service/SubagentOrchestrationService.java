package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 主 Agent 创建、读取和取消临时子 Agent 任务的领域服务。 */
@Service
public class SubagentOrchestrationService {
    private static final int MAX_TASKS = 20;
    private final ISubagentTaskRepository repository;
    private final AgentAvailabilityService availabilityService;
    private final RunControlService runControlService;

    public SubagentOrchestrationService(ISubagentTaskRepository repository,
                                        AgentAvailabilityService availabilityService,
                                        RunControlService runControlService) {
        this.repository = repository;
        this.availabilityService = availabilityService;
        this.runControlService = runControlService;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<SubagentTaskEntity> delegate(TrustedSupervisor supervisor, String functionCallId,
                                             List<TaskRequest> requests) {
        validateSupervisor(supervisor);
        if (functionCallId == null || functionCallId.isBlank()) throw error("SUBAGENT_FUNCTION_CALL_REQUIRED");
        // 锁必须早于幂等查询，否则取消/删除可能在查询和建批之间穿过。
        runControlService.lockParentForDelegation(supervisor.tenantId, supervisor.userId,
                supervisor.parentSessionId, supervisor.parentRunId, supervisor.parentAgentId);
        List<SubagentTaskEntity> replay = repository.queryByFunctionCall(supervisor.tenantId,
                supervisor.parentRunId, functionCallId);
        if (replay != null && !replay.isEmpty()) return replay;
        if (!repository.queryByIds(supervisor.tenantId, supervisor.parentRunId, List.of()).isEmpty()) {
            throw error("SUBAGENT_BATCH_ALREADY_CREATED");
        }
        if (requests == null || requests.isEmpty() || requests.size() > MAX_TASKS) {
            throw error("SUBAGENT_TASK_COUNT_INVALID");
        }
        Set<String> allowed = new HashSet<>(supervisor.allowedSubAgentIds);
        LocalDateTime now = LocalDateTime.now();
        List<SubagentTaskEntity> tasks = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            TaskRequest request = requests.get(index);
            if (request == null || !allowed.contains(request.agentId)
                    || request.instruction == null || request.instruction.isBlank()
                    || request.instruction.length() > 12000) {
                throw error("SUBAGENT_TASK_INVALID");
            }
            availabilityService.assertEnabled(supervisor.tenantId, request.agentId);
            tasks.add(SubagentTaskEntity.builder().tenantId(supervisor.tenantId).userId(supervisor.userId)
                    .parentRunId(supervisor.parentRunId).parentSessionId(supervisor.parentSessionId)
                    .parentAgentId(supervisor.parentAgentId)
                    .taskId(stableTaskId(supervisor, functionCallId, index)).childAgentId(request.agentId)
                    .instruction(request.instruction).functionCallId(functionCallId).traceId(supervisor.traceId)
                    .status(SubagentTaskStatus.READY).attempt(0).fencingToken(0L)
                    .summaryTruncated(false).createdAt(now).build());
        }
        if (repository.createBatchAndEnqueue(tasks) != tasks.size()) {
            // 另一容器可能在“首次查询”后先提交了同一 function call。
            List<SubagentTaskEntity> winner = repository.queryByFunctionCall(supervisor.tenantId,
                    supervisor.parentRunId, functionCallId);
            if (winner != null && winner.size() == tasks.size()) return winner;
            throw error("SUBAGENT_TASK_CREATE_FAILED");
        }
        return tasks;
    }

    private String stableTaskId(TrustedSupervisor supervisor, String functionCallId, int index) {
        String identity = supervisor.tenantId + '\0' + supervisor.parentRunId + '\0' + functionCallId + '\0' + index;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public List<SubagentTaskEntity> read(String tenantId, String parentRunId, List<String> taskIds) {
        requireText(tenantId); requireText(parentRunId);
        return repository.queryByIds(tenantId, parentRunId, taskIds == null ? List.of() : List.copyOf(taskIds));
    }

    public int cancel(String tenantId, String parentRunId, List<String> taskIds) {
        requireText(tenantId); requireText(parentRunId);
        if (taskIds == null || taskIds.isEmpty()) throw error("SUBAGENT_TASK_IDS_REQUIRED");
        return repository.cancel(tenantId, parentRunId, List.copyOf(taskIds), LocalDateTime.now());
    }

    private void validateSupervisor(TrustedSupervisor value) {
        if (value == null || !"SUPERVISOR".equalsIgnoreCase(value.role)
                || value.allowedSubAgentIds == null || value.allowedSubAgentIds.isEmpty()) {
            throw error("SUBAGENT_SUPERVISOR_REQUIRED");
        }
        requireText(value.tenantId); requireText(value.userId); requireText(value.parentRunId); requireText(value.parentSessionId);
        requireText(value.parentAgentId);
    }

    private void requireText(String value) { if (value == null || value.isBlank()) throw error("SUBAGENT_CONTEXT_INVALID"); }
    private AppException error(String code) { return new AppException(code, code); }

    public record TrustedSupervisor(String tenantId, String userId, String parentRunId, String parentSessionId,
                                    String parentAgentId,
                                    String role, List<String> allowedSubAgentIds, String traceId) { }
    public record TaskRequest(String agentId, String instruction) { }
}
