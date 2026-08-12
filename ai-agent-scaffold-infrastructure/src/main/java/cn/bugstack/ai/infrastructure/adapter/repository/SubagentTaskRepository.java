package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTaskPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MySQL 实现：任务状态与 Kafka Outbox 在同一事务中变更。 */
@Repository
public class SubagentTaskRepository implements ISubagentTaskRepository {
    private final ISubagentTaskDao dao;
    private final ObjectMapper objectMapper;

    public SubagentTaskRepository(ISubagentTaskDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createBatchAndEnqueue(List<SubagentTaskEntity> tasks) {
        int created = 0;
        for (SubagentTaskEntity task : tasks) {
            created += dao.insertTask(toPO(task));
            dao.insertOutbox(event(task.getTenantId(), "SUBAGENT_TASK_READY", task.getTaskId(),
                    task.getParentRunId(), Map.of("schemaVersion", 1, "taskId", task.getTaskId(), "tenantId", task.getTenantId(),
                            "parentRunId", task.getParentRunId(), "traceId", safe(task.getTraceId()))));
        }
        return created;
    }

    @Override
    public List<SubagentTaskEntity> queryByFunctionCall(String tenantId, String parentRunId, String functionCallId) {
        return dao.queryByFunctionCall(tenantId, parentRunId, functionCallId).stream().map(this::toEntity).toList();
    }

    @Override
    public List<SubagentTaskEntity> queryByIds(String tenantId, String parentRunId, List<String> taskIds) {
        return dao.queryByIds(tenantId, parentRunId, taskIds).stream().map(this::toEntity).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubagentTaskEntity claim(String tenantId, String taskId, String workerId, LocalDateTime now,
                                    Duration leaseDuration) {
        LocalDateTime expiresAt = now.plus(leaseDuration);
        if (dao.claim(tenantId, taskId, workerId, now, expiresAt) != 1) return null;
        return toEntity(dao.queryOwned(tenantId, taskId, workerId));
    }

    @Override
    public int renewLease(String tenantId, String taskId, String workerId, long fencingToken,
                          LocalDateTime now, Duration leaseDuration) {
        return dao.renewLease(tenantId, taskId, workerId, fencingToken, now, now.plus(leaseDuration));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int complete(SubagentTaskEntity task, String workerId, long fencingToken) {
        int changed = dao.complete(toPO(task), workerId, fencingToken);
        if (changed == 1) {
            dao.insertOutbox(event(task.getTenantId(), "SUBAGENT_RESULT_READY", task.getTaskId(),
                    task.getParentRunId(), Map.of("schemaVersion", 1, "taskId", task.getTaskId(), "tenantId", task.getTenantId(),
                            "parentRunId", task.getParentRunId(), "status", task.getStatus().name(),
                            "traceId", safe(task.getTraceId()))));
        }
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancel(String tenantId, String parentRunId, List<String> taskIds, LocalDateTime cancelledAt) {
        int changed = dao.cancel(tenantId, parentRunId, taskIds, cancelledAt);
        if (changed > 0) taskIds.forEach(taskId -> dao.insertOutbox(event(tenantId,
                "SUBAGENT_INSTANCE_CLEANUP", taskId, parentRunId,
                Map.of("schemaVersion", 1, "taskId", taskId, "tenantId", tenantId,
                        "parentRunId", parentRunId))));
        return changed;
    }

    @Override
    public boolean claimCallback(String tenantId, String taskId, String callbackOwner, LocalDateTime now) {
        return dao.claimCallback(tenantId, taskId, callbackOwner, now) == 1;
    }

    @Override
    public int retryCallback(String tenantId, String taskId, String callbackOwner, String error) {
        String safeError = error == null ? "UNKNOWN" : error.substring(0, Math.min(1000, error.length()));
        return dao.retryCallback(tenantId, taskId, callbackOwner, safeError);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int finishCallback(String tenantId, String parentRunId, String taskId,
                              String callbackOwner, LocalDateTime deliveredAt) {
        int changed = dao.finishCallback(tenantId, parentRunId, taskId, callbackOwner, deliveredAt);
        if (changed == 1) {
            dao.insertOutbox(event(tenantId, "SUBAGENT_INSTANCE_CLEANUP", taskId, parentRunId,
                    Map.of("schemaVersion", 1, "taskId", taskId, "tenantId", tenantId,
                            "parentRunId", parentRunId)));
        }
        return changed;
    }

    private AgentOrchestrationOutboxPO event(String tenantId, String type, String aggregateId,
                                             String partitionKey, Map<String, Object> payload) {
        AgentOrchestrationOutboxPO value = new AgentOrchestrationOutboxPO();
        value.setTenantId(tenantId); value.setEventId(UUID.randomUUID().toString()); value.setEventType(type);
        value.setAggregateId(aggregateId); value.setPartitionKey(partitionKey); value.setPayload(json(payload));
        value.setStatus("PENDING"); value.setAttemptCount(0); value.setMaxAttempts(12);
        value.setNextAttemptAt(LocalDateTime.now()); value.setFencingToken(0L); value.setCreatedAt(LocalDateTime.now());
        return value;
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("SUBAGENT_EVENT_SERIALIZE_FAILED", exception); }
    }

    private String safe(String value) { return value == null ? "" : value; }

    private SubagentTaskPO toPO(SubagentTaskEntity value) {
        SubagentTaskPO po = new SubagentTaskPO();
        po.setTenantId(value.getTenantId()); po.setUserId(value.getUserId()); po.setParentRunId(value.getParentRunId());
        po.setParentSessionId(value.getParentSessionId());
        po.setParentAgentId(value.getParentAgentId()); po.setTaskId(value.getTaskId()); po.setChildAgentId(value.getChildAgentId());
        po.setInstruction(value.getInstruction()); po.setFunctionCallId(value.getFunctionCallId()); po.setTraceId(value.getTraceId());
        po.setStatus(value.getStatus().name()); po.setAttempt(value.getAttempt()); po.setFencingToken(value.getFencingToken());
        po.setLeaseOwner(value.getLeaseOwner()); po.setLeaseExpiresAt(value.getLeaseExpiresAt());
        po.setResultText(value.getResultText()); po.setErrorCode(value.getErrorCode()); po.setCreatedAt(value.getCreatedAt());
        po.setCompletedAt(value.getCompletedAt()); po.setAcknowledgedAt(value.getAcknowledgedAt()); return po;
    }

    private SubagentTaskEntity toEntity(SubagentTaskPO value) {
        if (value == null) return null;
        return SubagentTaskEntity.builder().tenantId(value.getTenantId()).userId(value.getUserId())
                .parentRunId(value.getParentRunId()).parentSessionId(value.getParentSessionId())
                .parentAgentId(value.getParentAgentId()).taskId(value.getTaskId())
                .childAgentId(value.getChildAgentId()).instruction(value.getInstruction()).functionCallId(value.getFunctionCallId())
                .traceId(value.getTraceId()).status(SubagentTaskStatus.valueOf(value.getStatus())).attempt(value.getAttempt())
                .fencingToken(value.getFencingToken()).leaseOwner(value.getLeaseOwner()).leaseExpiresAt(value.getLeaseExpiresAt())
                .resultText(value.getResultText()).errorCode(value.getErrorCode()).createdAt(value.getCreatedAt())
                .completedAt(value.getCompletedAt()).acknowledgedAt(value.getAcknowledgedAt()).build();
    }
}
