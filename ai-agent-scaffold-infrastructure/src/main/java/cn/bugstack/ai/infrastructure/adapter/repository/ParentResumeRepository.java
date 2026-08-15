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
    public void prepareWait(SubagentTaskEntity task, LocalDateTime now) {
        dao.prepareWait(request(task, now));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean tryPrepareWait(SubagentTaskEntity task, LocalDateTime now) {
        return dao.insertWaitOnce(request(task, now)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markParentReady(String tenantId, String parentRunId, String parentDraft, LocalDateTime now) {
        if (dao.markParentReady(tenantId, parentRunId, parentDraft, now) != 1) return false;
        activateAndNotify(tenantId, parentRunId, null, now);
        return true;
    }

    @Override
    public boolean isAwaitingSummary(String tenantId, String parentRunId) {
        return dao.countAwaitingSummary(tenantId, parentRunId) > 0;
    }

    @Override
    public String queryStatus(String tenantId, String parentRunId) {
        ParentResumeRequestPO request = dao.query(tenantId, parentRunId);
        return request == null ? null : request.getStatus();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean tryActivate(String tenantId, String parentRunId, LocalDateTime now) {
        return activateAndNotify(tenantId, parentRunId, null, now);
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
        // 作为旧调用方未及时 prepareWait 时的持久化保险，不会越过 parent_ready 屏障。
        dao.prepareWait(request(task, now));
        activateAndNotify(task.getTenantId(), task.getParentRunId(), task.getTraceId(), now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ParentResumeBatchEntity claim(String tenantId, String parentRunId, String workerId,
                                         LocalDateTime now, Duration leaseDuration, int limit) {
        if (dao.claim(tenantId, parentRunId, workerId, now, now.plus(leaseDuration)) != 1) return null;
        ParentResumeRequestPO request = dao.queryOwned(tenantId, parentRunId, workerId);
        if (request == null) return null;
        List<ParentResumeBatchEntity.InboxItem> items = dao.queryAllTerminalResults(tenantId, parentRunId).stream()
                .map(value -> new ParentResumeBatchEntity.InboxItem(value.getSequence(), value.getTaskId(),
                        value.getChildAgentId(), value.getResultSummary(), value.getTaskStatus(),
                        value.getCallbackStatus())).toList();
        return ParentResumeBatchEntity.builder().tenantId(request.getTenantId()).userId(request.getUserId())
                .parentRunId(request.getParentRunId()).parentSessionId(request.getParentSessionId())
                .parentAgentId(request.getParentAgentId()).traceId(request.getTraceId())
                .requestedVersion(request.getRequestedVersion()).fencingToken(request.getFencingToken())
                .parentDraft(request.getParentDraft()).items(items).build();
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
        for (ParentResumeBatchEntity.InboxItem item : batch.getItems()) {
            // CANCELLED/DEAD 链路已即时发出清理事件；仍推进 DEAD 为 DELIVERED，但不重复副作用。
            if ("CANCELLED".equals(item.taskStatus())) continue;
            if (dao.finishTaskDelivery(batch.getTenantId(), batch.getParentRunId(), item.taskId(), deliveredAt) == 1) {
                if (!"DEAD".equals(item.callbackStatus()) && item.sequence() > 0) {
                    dao.insertOutbox(cleanupEvent(batch.getTenantId(), batch.getParentRunId(), item.taskId(), deliveredAt));
                }
            }
        }
        return changed;
    }

    @Override
    public int renewLease(String tenantId, String parentRunId, String workerId, long fencingToken,
                          LocalDateTime now, Duration leaseDuration) {
        return dao.renewLease(tenantId, parentRunId, workerId, fencingToken, now, now.plus(leaseDuration));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean lockOwnedLease(String tenantId, String parentRunId, String workerId,
                                  long fencingToken, LocalDateTime now) {
        return dao.lockOwnedLease(tenantId, parentRunId, workerId, fencingToken, now) != null;
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
        // 通知去重仍使用冷却窗口；父侧判活只认 chat_run 终态，不以静默时长猜测进程死亡。
        LocalDateTime staleBefore = now.minusMinutes(15);
        for (ParentResumeRequestPO request : dao.queryRecoveryCandidates(now, staleBefore, limit)) {
            if ("WAITING".equals(request.getStatus()) && !Boolean.TRUE.equals(request.getParentReady())) {
                // 与正常收口保持 run -> resume 锁序；运行仍可执行时绝不由 WAIT_ALL 强制终结。
                if (dao.lockTerminalParentRun(request.getTenantId(), request.getUserId(),
                        request.getParentRunId()) == null) continue;
                if (dao.markRecoveryNotified(request.getTenantId(), request.getParentRunId(), request.getStatus(),
                        request.getFencingToken(), now, staleBefore) == 1) {
                    String draft = "[platform_recovery] 父 Agent 已进入终态但未打开汇总屏障；请基于全部子任务结果完成一次最终汇总。";
                    if (dao.markParentReady(request.getTenantId(), request.getParentRunId(), draft, now) == 1) {
                        activateAndNotify(request.getTenantId(), request.getParentRunId(), request.getTraceId(), now);
                    }
                    recovered++;
                }
            } else if (dao.markRecoveryNotified(request.getTenantId(), request.getParentRunId(), request.getStatus(),
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

    private boolean activateAndNotify(String tenantId, String parentRunId, String traceId, LocalDateTime now) {
        if (dao.activateIfReady(tenantId, parentRunId, now) != 1) return false;
        String effectiveTraceId = traceId;
        if (effectiveTraceId == null) {
            ParentResumeRequestPO request = dao.query(tenantId, parentRunId);
            effectiveTraceId = request == null ? null : request.getTraceId();
        }
        dao.insertOutbox(event(tenantId, parentRunId, effectiveTraceId, now));
        return true;
    }

    private ParentResumeRequestPO request(SubagentTaskEntity task, LocalDateTime now) {
        ParentResumeRequestPO request = new ParentResumeRequestPO();
        request.setTenantId(task.getTenantId()); request.setUserId(task.getUserId());
        request.setParentRunId(task.getParentRunId()); request.setParentSessionId(task.getParentSessionId());
        request.setParentAgentId(task.getParentAgentId()); request.setTraceId(task.getTraceId());
        request.setStatus("WAITING"); request.setParentReady(false); request.setNextAttemptAt(now);
        return request;
    }
}
