package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagLease;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

/** MySQL知识库删除任务账本及聚合受理事务。 */
@Repository
@RequiredArgsConstructor
public class RagKnowledgeBaseDeletionRepositoryImpl implements RagKnowledgeBaseDeletionRepository {

    private final IRagKnowledgeBaseDeleteTaskDao taskDao;
    private final IRagKnowledgeBaseDao knowledgeBaseDao;
    private final IRagDocumentDao documentDao;
    private final IRagDocumentVersionDao documentVersionDao;
    private final IRagChunkDao chunkDao;
    private final IRagIngestTaskDao ingestTaskDao;
    private final IRagAgentBindingDao bindingDao;
    private final RagPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<RagKnowledgeBaseDeleteTaskEntity> findByTaskId(String tenantId, String taskId) {
        return Optional.ofNullable(toEntity(taskDao.queryByTenantAndTaskId(
                requireText(tenantId), requireText(taskId))));
    }

    @Override
    public Optional<RagKnowledgeBaseDeleteTaskEntity> findByKnowledgeBaseId(String tenantId,
                                                                            String knowledgeBaseId) {
        return Optional.ofNullable(toEntity(taskDao.queryByTenantAndKnowledgeBaseId(
                requireText(tenantId), requireText(knowledgeBaseId))));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean register(String tenantId, RagKnowledgeBaseDeleteRegistration registration) {
        requireScope(tenantId, registration);
        RagKnowledgeBaseEntity locked = mapper.toKnowledgeBase(
                knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate(
                        tenantId, registration.knowledgeBase().knowledgeBaseId()));
        if (locked == null) throw new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在");
        if (taskDao.queryByTenantAndKnowledgeBaseId(tenantId, locked.knowledgeBaseId()) != null) return false;
        if (locked.revision() != registration.expectedKnowledgeBaseRevision()
                || locked.status() != RagKnowledgeBaseStatus.ACTIVE
                && locked.status() != RagKnowledgeBaseStatus.DISABLED) {
            throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", "知识库已变化，请刷新后重试");
        }
        int activeTasks = ingestTaskDao.countActiveByTenantAndKnowledgeBaseId(
                tenantId, locked.knowledgeBaseId());
        if (activeTasks > 0) {
            throw new AppException("RAG_KNOWLEDGE_BASE_TASKS_ACTIVE",
                    "知识库仍有活动摄取任务，请先等待或取消");
        }
        int totalDocuments = documentDao.countByTenantAndKnowledgeBaseId(tenantId, locked.knowledgeBaseId());
        if (registration.task().checkpoint().totalDocuments() != totalDocuments) {
            throw new AppException("RAG_KNOWLEDGE_BASE_DOCUMENT_SET_CHANGED", "知识库文档集合已变化");
        }
        try {
            if (taskDao.insert(toPo(registration.task())) != 1) return false;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        if (knowledgeBaseDao.updateByTenantAndRevision(tenantId,
                mapper.toKnowledgeBasePo(registration.knowledgeBase()),
                registration.expectedKnowledgeBaseRevision()) != 1) {
            throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", "知识库已变化，请刷新后重试");
        }
        bindingDao.disableByTenantAndKnowledgeBaseId(tenantId, locked.knowledgeBaseId());
        return true;
    }

    @Override
    public int update(String tenantId, RagKnowledgeBaseDeleteTaskEntity task, long expectedRevision) {
        if (task == null || !requireText(tenantId).equals(task.tenantId()) || expectedRevision < 0) {
            throw new IllegalArgumentException("知识库删除任务更新范围非法");
        }
        return taskDao.updateByTenantAndRevision(tenantId, toPo(task), expectedRevision);
    }

    @Override
    public List<RagKnowledgeBaseDeleteCandidate> listDueCandidates(java.time.Instant now, int limit) {
        if (now == null || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("知识库删除扫描参数非法");
        }
        return taskDao.queryDueCandidates(toLocal(now), limit).stream()
                .map(value -> new RagKnowledgeBaseDeleteCandidate(value.tenantId(), value.taskId()))
                .toList();
    }

    @Override
    public Optional<RagKnowledgeBaseDeleteTaskEntity> claim(String tenantId, String taskId,
                                                              String leaseOwner, java.time.Instant now,
                                                              java.time.Instant leaseUntil) {
        requireLeaseWindow(now, leaseUntil);
        if (taskDao.claimDue(requireText(tenantId), requireText(taskId), requireText(leaseOwner),
                toLocal(now), toLocal(leaseUntil)) != 1) return Optional.empty();
        return findByTaskId(tenantId, taskId);
    }

    @Override
    public int heartbeat(String tenantId, String taskId, String leaseOwner, long fencingToken,
                         java.time.Instant now, java.time.Instant leaseUntil) {
        requireLeaseWindow(now, leaseUntil);
        if (fencingToken < 1) throw new IllegalArgumentException("知识库删除栅栏非法");
        return taskDao.heartbeatClaimed(requireText(tenantId), requireText(taskId),
                requireText(leaseOwner), fencingToken, toLocal(now), toLocal(leaseUntil));
    }

    @Override
    public int updateClaimed(String tenantId, RagKnowledgeBaseDeleteTaskEntity task,
                             long expectedRevision, String leaseOwner, long fencingToken,
                             java.time.Instant now) {
        if (task == null || !requireText(tenantId).equals(task.tenantId())
                || expectedRevision < 0 || fencingToken < 1 || now == null) {
            throw new IllegalArgumentException("知识库删除任务围栏更新非法");
        }
        RagKnowledgeBaseDeleteTaskPO po = toPo(task);
        po.setHeartbeatAt(task.lease() == null ? null : toLocal(now));
        return taskDao.updateClaimedByTenantFenceAndRevision(tenantId, po, expectedRevision,
                requireText(leaseOwner), fencingToken, toLocal(now));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeClaimed(String tenantId, String taskId, long expectedTaskRevision,
                                String leaseOwner, long fencingToken, java.time.Instant now) {
        String trustedTenant = requireText(tenantId);
        RagKnowledgeBaseDeleteTaskPO taskSnapshot = taskDao.queryByTenantAndTaskId(
                trustedTenant, requireText(taskId));
        if (taskSnapshot == null) {
            throw new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在");
        }
        // 快照读只用于获取kbId；真正加锁始终按 KB -> task 的全局顺序。
        RagKnowledgeBaseEntity knowledgeBase = mapper.toKnowledgeBase(
                knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate(
                        trustedTenant, taskSnapshot.getKnowledgeBaseId()));
        if (knowledgeBase == null || knowledgeBase.status() != RagKnowledgeBaseStatus.DELETING) {
            throw new AppException("RAG_KB_DELETE_STATE_MISMATCH", "知识库与删除任务状态不一致");
        }
        RagKnowledgeBaseDeleteTaskPO lockedTask = taskDao.queryByTenantAndTaskIdForUpdate(
                trustedTenant, taskId);
        if (lockedTask == null || !knowledgeBase.knowledgeBaseId().equals(lockedTask.getKnowledgeBaseId())) {
            throw new AppException("RAG_KB_DELETE_CONCURRENT_UPDATE", "知识库删除任务已变化");
        }
        RagKnowledgeBaseDeleteTaskEntity current = toEntity(lockedTask);
        if (!knowledgeBase.knowledgeBaseId().equals(current.knowledgeBaseId())
                || current.revision() != expectedTaskRevision) {
            throw new AppException("RAG_KB_DELETE_CONCURRENT_UPDATE", "知识库删除任务已变化");
        }
        current.assertClaim(requireText(leaseOwner), fencingToken, now);
        RagKnowledgeBaseDeleteTaskEntity completed = current.complete(leaseOwner, fencingToken, now);
        String kbId = knowledgeBase.knowledgeBaseId();
        int documents = documentDao.countByTenantAndKnowledgeBaseId(trustedTenant, kbId);
        boolean clean = documents == current.checkpoint().totalDocuments()
                && documentDao.countNotDeletedByTenantAndKnowledgeBaseId(trustedTenant, kbId) == 0
                && documentVersionDao.countNotDeletedByTenantAndKnowledgeBaseId(trustedTenant, kbId) == 0
                && chunkDao.countAllByTenantAndKnowledgeBaseId(trustedTenant, kbId) == 0L
                && ingestTaskDao.countActiveByTenantAndKnowledgeBaseId(trustedTenant, kbId) == 0
                && ingestTaskDao.countDocumentsWithoutCompletedDelete(trustedTenant, kbId) == 0
                && bindingDao.countActiveByTenantAndKnowledgeBaseId(trustedTenant, kbId) == 0;
        if (!clean) {
            throw new AppException("RAG_KB_DELETE_RESIDUALS", "知识库删除仍存在未清理完成的子项");
        }
        if (knowledgeBaseDao.updateByTenantAndRevision(trustedTenant,
                mapper.toKnowledgeBasePo(knowledgeBase.deleted()), knowledgeBase.revision()) != 1) {
            throw new AppException("RAG_KB_DELETE_CONCURRENT_UPDATE", "知识库删除收口发生并发变化");
        }
        RagKnowledgeBaseDeleteTaskPO completedPo = toPo(completed);
        completedPo.setHeartbeatAt(null);
        if (taskDao.updateClaimedByTenantFenceAndRevision(trustedTenant, completedPo,
                expectedTaskRevision, leaseOwner, fencingToken, toLocal(now)) != 1) {
            throw new AppException("RAG_KB_DELETE_CONCURRENT_UPDATE", "知识库删除任务收口发生并发变化");
        }
    }

    private RagKnowledgeBaseDeleteTaskPO toPo(RagKnowledgeBaseDeleteTaskEntity task) {
        try {
            return RagKnowledgeBaseDeleteTaskPO.builder()
                    .taskId(task.taskId()).taskKey(task.taskKey()).tenantId(task.tenantId())
                    .knowledgeBaseId(task.knowledgeBaseId()).requestedByUserId(task.requestedByUserId())
                    .status(task.status().name().toLowerCase())
                    .checkpoint(objectMapper.writeValueAsString(task.checkpoint()))
                    .attemptCount(task.attemptCount()).maxAttempts(task.maxAttempts())
                    .nextRetryAt(toLocal(task.nextRetryAt()))
                    .leaseOwner(task.lease() == null ? null : task.lease().owner())
                    .leaseUntil(task.lease() == null ? null : toLocal(task.lease().expiresAt()))
                    .heartbeatAt(null)
                    .fencingToken(task.fencingToken()).rowVersion(task.revision())
                    .errorCode(task.errorCode()).errorMessage(task.errorMessage()).build();
        } catch (Exception e) {
            throw new IllegalStateException("知识库删除检查点序列化失败", e);
        }
    }

    private RagKnowledgeBaseDeleteTaskEntity toEntity(RagKnowledgeBaseDeleteTaskPO po) {
        if (po == null) return null;
        try {
            JsonNode root = objectMapper.readTree(po.getCheckpoint());
            RagKnowledgeBaseDeleteCheckpoint checkpoint = new RagKnowledgeBaseDeleteCheckpoint(
                    RagKnowledgeBaseDeleteStage.valueOf(root.path("stage").asText().toUpperCase()),
                    root.path("totalDocuments").asInt(), root.path("completedDocuments").asInt(),
                    root.path("currentDocumentId").isNull() || root.path("currentDocumentId").isMissingNode()
                            ? null : root.path("currentDocumentId").asText());
            RagLease lease = po.getLeaseOwner() == null || po.getLeaseUntil() == null ? null
                    : new RagLease(po.getLeaseOwner(), po.getLeaseUntil().toInstant(ZoneOffset.UTC));
            return new RagKnowledgeBaseDeleteTaskEntity(po.getTenantId(), po.getKnowledgeBaseId(),
                    po.getRequestedByUserId(),
                    po.getTaskId(), po.getTaskKey(), RagKnowledgeBaseDeleteStatus.valueOf(
                    po.getStatus().toUpperCase()), checkpoint, po.getAttemptCount(), po.getMaxAttempts(),
                    toInstant(po.getNextRetryAt()), lease, po.getFencingToken(), po.getRowVersion(),
                    po.getErrorCode(), po.getErrorMessage());
        } catch (Exception e) {
            throw new AppException("RAG_KB_DELETE_CHECKPOINT_INVALID", "知识库删除检查点无法读取", e);
        }
    }

    private void requireScope(String tenantId, RagKnowledgeBaseDeleteRegistration registration) {
        if (registration == null || !requireText(tenantId).equals(registration.knowledgeBase().tenantId())
                || !tenantId.equals(registration.task().tenantId())) {
            throw new IllegalArgumentException("知识库删除登记租户范围非法");
        }
    }

    private String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("标识符不能为空");
        return value;
    }

    private LocalDateTime toLocal(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private void requireLeaseWindow(java.time.Instant now, java.time.Instant leaseUntil) {
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("知识库删除租约时间非法");
        }
    }
}
