package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.model.entity.ParentResumeBatchEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.infrastructure.dao.IParentResumeDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.infrastructure.dao.po.ParentInboxItemPO;
import cn.bugstack.ai.infrastructure.dao.po.ParentResumeRequestPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MySQL Parent Inbox + Resume Request，Redis 仅作为可降级索引。 */
@Repository
public class ParentResumeRepository implements IParentResumeRepository {
    private final IParentResumeDao dao;
    private final ObjectMapper objectMapper;

    public ParentResumeRepository(IParentResumeDao dao, ObjectMapper objectMapper) {
        this.dao = dao; this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean registerResult(SubagentTaskEntity task, String callbackOwner, LocalDateTime now) {
        if (dao.markCallbackRegistered(task.getTenantId(), task.getTaskId(), callbackOwner) != 1) return false;
        ParentInboxItemPO item = new ParentInboxItemPO();
        item.setTenantId(task.getTenantId()); item.setParentRunId(task.getParentRunId()); item.setTaskId(task.getTaskId());
        item.setChildAgentId(task.getChildAgentId()); item.setResultSummary(task.getResultSummary());
        item.setTaskStatus(task.getStatus().name());
        dao.insertInbox(item);
        ParentResumeRequestPO request = new ParentResumeRequestPO();
        request.setTenantId(task.getTenantId()); request.setUserId(task.getUserId());
        request.setParentRunId(task.getParentRunId()); request.setParentSessionId(task.getParentSessionId());
        request.setParentAgentId(task.getParentAgentId()); request.setTraceId(task.getTraceId());
        request.setStatus("PENDING"); request.setNextAttemptAt(now);
        dao.upsertRequest(request);
        dao.insertOutbox(event(task.getTenantId(), task.getParentRunId(), task.getTraceId(), now));
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParentResumeBatchEntity claim(String tenantId, String parentRunId, String workerId,
                                         LocalDateTime now, Duration leaseDuration, int limit) {
        if (dao.claim(tenantId, parentRunId, workerId, now, now.plus(leaseDuration)) != 1) return null;
        ParentResumeRequestPO request = dao.queryOwned(tenantId, parentRunId, workerId);
        if (request == null) return null;
        List<ParentResumeBatchEntity.InboxItem> items = dao.queryInbox(tenantId, parentRunId,
                request.getInboxCursor() == null ? 0L : request.getInboxCursor(), limit).stream()
                .map(value -> new ParentResumeBatchEntity.InboxItem(value.getSequence(), value.getTaskId(),
                        value.getChildAgentId(), value.getResultSummary(), value.getTaskStatus())).toList();
        return ParentResumeBatchEntity.builder().tenantId(request.getTenantId()).userId(request.getUserId())
                .parentRunId(request.getParentRunId()).parentSessionId(request.getParentSessionId())
                .parentAgentId(request.getParentAgentId()).traceId(request.getTraceId())
                .requestedVersion(request.getRequestedVersion()).fencingToken(request.getFencingToken())
                .items(items).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int complete(ParentResumeBatchEntity batch, String workerId, long fencingToken,
                        LocalDateTime deliveredAt) {
        int changed = dao.completeRequest(batch.getTenantId(), batch.getParentRunId(), workerId, fencingToken,
                batch.getRequestedVersion(), batch.lastSequence(), deliveredAt);
        if (changed != 1) return 0;
        List<String> taskIds = batch.getItems().stream().map(ParentResumeBatchEntity.InboxItem::taskId).toList();
        if (!taskIds.isEmpty()) dao.markInboxConsumed(batch.getTenantId(), batch.getParentRunId(), taskIds, deliveredAt);
        for (String taskId : taskIds) {
            if (dao.finishTaskDelivery(batch.getTenantId(), batch.getParentRunId(), taskId, deliveredAt) == 1) {
                dao.insertOutbox(cleanupEvent(batch.getTenantId(), batch.getParentRunId(), taskId, deliveredAt));
            }
        }
        ParentResumeRequestPO current = dao.query(batch.getTenantId(), batch.getParentRunId());
        if (changed == 1 && current != null && "PENDING".equals(current.getStatus())) {
            dao.insertOutbox(event(batch.getTenantId(), batch.getParentRunId(), batch.getTraceId(), deliveredAt));
        }
        return changed;
    }

    @Override
    public int renewLease(String tenantId, String parentRunId, String workerId, long fencingToken,
                          LocalDateTime now, Duration leaseDuration) {
        return dao.renewLease(tenantId, parentRunId, workerId, fencingToken, now, now.plus(leaseDuration));
    }

    @Override
    public int retry(String tenantId, String parentRunId, String workerId, long fencingToken,
                     LocalDateTime nextAttemptAt, String error) {
        String safe = error == null ? "UNKNOWN" : error.substring(0, Math.min(1000, error.length()));
        return dao.retry(tenantId, parentRunId, workerId, fencingToken, nextAttemptAt, safe);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverDue(LocalDateTime now, int limit) {
        int recovered = 0;
        LocalDateTime staleBefore = now.minusSeconds(30);
        for (ParentResumeRequestPO request : dao.queryRecoveryCandidates(now, staleBefore, limit)) {
            if (dao.markRecoveryNotified(request.getTenantId(), request.getParentRunId(), request.getStatus(),
                    request.getFencingToken(), now, staleBefore) == 1) {
                dao.insertOutbox(event(request.getTenantId(), request.getParentRunId(), request.getTraceId(), now));
                recovered++;
            }
        }
        return recovered;
    }

    private AgentOrchestrationOutboxPO event(String tenantId, String parentRunId, String traceId, LocalDateTime now) {
        return outbox(tenantId, "PARENT_RESUME_REQUESTED", parentRunId, parentRunId,
                Map.of("schemaVersion", 1, "tenantId", tenantId, "parentRunId", parentRunId,
                        "traceId", traceId == null ? "" : traceId), now);
    }

    private AgentOrchestrationOutboxPO cleanupEvent(String tenantId, String parentRunId, String taskId,
                                                     LocalDateTime now) {
        return outbox(tenantId, "SUBAGENT_INSTANCE_CLEANUP", taskId, parentRunId,
                Map.of("schemaVersion", 1, "tenantId", tenantId, "parentRunId", parentRunId, "taskId", taskId), now);
    }

    private AgentOrchestrationOutboxPO outbox(String tenantId, String type, String aggregateId, String partitionKey,
                                               Map<String, Object> payload, LocalDateTime now) {
        AgentOrchestrationOutboxPO value = new AgentOrchestrationOutboxPO();
        value.setTenantId(tenantId); value.setEventId(UUID.randomUUID().toString()); value.setEventType(type);
        value.setAggregateId(aggregateId); value.setPartitionKey(partitionKey); value.setPayload(json(payload));
        value.setStatus("PENDING"); value.setAttemptCount(0); value.setMaxAttempts(12);
        value.setNextAttemptAt(now); value.setFencingToken(0L); value.setCreatedAt(now); return value;
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("PARENT_RESUME_EVENT_INVALID", exception); }
    }
}
