package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    public SubagentOrchestrationService(ISubagentTaskRepository repository,
                                        AgentAvailabilityService availabilityService) {
        this.repository = repository;
        this.availabilityService = availabilityService;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<SubagentTaskEntity> delegate(TrustedSupervisor supervisor, String functionCallId,
                                             List<TaskRequest> requests) {
        validateSupervisor(supervisor);
        if (functionCallId == null || functionCallId.isBlank()) throw error("SUBAGENT_FUNCTION_CALL_REQUIRED");
        List<SubagentTaskEntity> replay = repository.queryByFunctionCall(supervisor.tenantId,
                supervisor.parentRunId, functionCallId);
        if (replay != null && !replay.isEmpty()) return replay;
        if (requests == null || requests.isEmpty() || requests.size() > MAX_TASKS) {
            throw error("SUBAGENT_TASK_COUNT_INVALID");
        }
        Set<String> allowed = new HashSet<>(supervisor.allowedSubAgentIds);
        LocalDateTime now = LocalDateTime.now();
        List<SubagentTaskEntity> tasks = requests.stream().map(request -> {
            if (request == null || !allowed.contains(request.agentId)
                    || request.instruction == null || request.instruction.isBlank()) {
                throw error("SUBAGENT_TASK_INVALID");
            }
            availabilityService.assertEnabled(supervisor.tenantId, request.agentId);
            return SubagentTaskEntity.builder().tenantId(supervisor.tenantId).userId(supervisor.userId)
                    .parentRunId(supervisor.parentRunId).parentSessionId(supervisor.parentSessionId)
                    .parentAgentId(supervisor.parentAgentId)
                    .taskId(UUID.randomUUID().toString()).childAgentId(request.agentId)
                    .instruction(request.instruction).functionCallId(functionCallId).traceId(supervisor.traceId)
                    .status(SubagentTaskStatus.READY).attempt(0).fencingToken(0L).createdAt(now).build();
        }).toList();
        if (repository.createBatchAndEnqueue(tasks) != tasks.size()) throw error("SUBAGENT_TASK_CREATE_FAILED");
        return tasks;
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
