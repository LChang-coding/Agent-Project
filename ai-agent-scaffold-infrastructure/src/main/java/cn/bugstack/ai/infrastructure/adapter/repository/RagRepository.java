package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.RagChunkPO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestCandidatePO;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import cn.bugstack.ai.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MySQL 强租户条件、乐观锁和任务 fencing 的 RAG 仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class RagRepository implements IRagRepository {

    private final IRagKnowledgeBaseDao knowledgeBaseDao;
    private final IRagDocumentDao documentDao;
    private final IRagDocumentVersionDao documentVersionDao;
    private final IRagIngestTaskDao ingestTaskDao;
    private final IRagChunkDao chunkDao;
    private final IRagRetrievalProfileDao retrievalProfileDao;
    private final IRagAgentBindingDao agentBindingDao;
    private final RagPersistenceMapper mapper;
    private final RagPersistenceCodec codec;

    @Override
    public Optional<RagKnowledgeBaseEntity> findKnowledgeBase(String tenantId, String knowledgeBaseId) {
        return Optional.ofNullable(mapper.toKnowledgeBase(
                knowledgeBaseDao.queryByTenantAndKnowledgeBaseId(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))));
    }

    @Override
    public List<RagKnowledgeBaseEntity> listKnowledgeBases(String tenantId) {
        return knowledgeBaseDao.queryListByTenantId(requireText(tenantId, "tenantId")).stream()
                .map(mapper::toKnowledgeBase).toList();
    }

    @Override
    public int insertKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase) {
        requireTenant(tenantId, knowledgeBase == null ? null : knowledgeBase.tenantId());
        try {
            return knowledgeBaseDao.insert(mapper.toKnowledgeBasePo(knowledgeBase));
        } catch (DuplicateKeyException e) {
            throw new AppException("RAG_KNOWLEDGE_BASE_CONFLICT",
                    "当前租户已存在同名知识库，请更换名称后重试", e);
        }
    }

    @Override
    public int updateKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase, long expectedRevision) {
        requireTenant(tenantId, knowledgeBase == null ? null : knowledgeBase.tenantId());
        requireRevision(expectedRevision);
        return knowledgeBaseDao.updateByTenantAndRevision(tenantId,
                mapper.toKnowledgeBasePo(knowledgeBase), expectedRevision);
    }

    @Override
    public Optional<RagDocumentEntity> findDocument(String tenantId, String documentId) {
        return Optional.ofNullable(mapper.toDocument(documentDao.queryByTenantAndDocumentId(
                requireText(tenantId, "tenantId"), requireText(documentId, "documentId"))));
    }

    @Override
    public List<RagDocumentEntity> listDocuments(String tenantId, String knowledgeBaseId) {
        return documentDao.queryListByTenantAndKnowledgeBaseId(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId")).stream()
                .map(mapper::toDocument).toList();
    }

    @Override
    public int insertDocument(String tenantId, RagDocumentEntity document) {
        requireTenant(tenantId, document == null ? null : document.tenantId());
        return documentDao.insert(mapper.toDocumentPo(document));
    }

    @Override
    public int updateDocument(String tenantId, RagDocumentEntity document, long expectedRevision) {
        requireTenant(tenantId, document == null ? null : document.tenantId());
        requireRevision(expectedRevision);
        return documentDao.updateByTenantAndRevision(tenantId, mapper.toDocumentPo(document), expectedRevision);
    }

    @Override
    public Optional<RagDocumentVersionEntity> findDocumentVersion(String tenantId, String versionId) {
        return Optional.ofNullable(mapper.toDocumentVersion(documentVersionDao.queryByTenantAndVersionId(
                requireText(tenantId, "tenantId"), requireText(versionId, "versionId"))));
    }

    @Override
    public List<RagDocumentVersionEntity> listDocumentVersions(String tenantId, String documentId) {
        return documentVersionDao.queryListByTenantAndDocumentId(requireText(tenantId, "tenantId"),
                        requireText(documentId, "documentId")).stream()
                .map(mapper::toDocumentVersion).toList();
    }

    @Override
    public int insertDocumentVersion(String tenantId, RagDocumentVersionEntity version) {
        requireTenant(tenantId, version == null ? null : version.tenantId());
        return documentVersionDao.insert(mapper.toDocumentVersionPo(version));
    }

    @Override
    public int updateDocumentVersion(String tenantId, RagDocumentVersionEntity version, long expectedRevision) {
        requireTenant(tenantId, version == null ? null : version.tenantId());
        requireRevision(expectedRevision);
        return documentVersionDao.updateByTenantAndRevision(tenantId,
                mapper.toDocumentVersionPo(version), expectedRevision);
    }

    @Override
    public Optional<RagIngestJobEntity> findIngestJob(String tenantId, String jobId) {
        return Optional.ofNullable(mapper.toIngestJob(ingestTaskDao.queryByTenantAndTaskId(
                requireText(tenantId, "tenantId"), requireText(jobId, "jobId"))));
    }

    @Override
    public Optional<RagIngestJobEntity> findIngestJobByIdempotencyKey(String tenantId, String idempotencyKey) {
        return Optional.ofNullable(mapper.toIngestJob(ingestTaskDao.queryByTenantAndTaskKey(
                requireText(tenantId, "tenantId"), requireText(idempotencyKey, "idempotencyKey"))));
    }

    @Override
    public int insertIngestJob(String tenantId, RagIngestJobEntity job) {
        requireTenant(tenantId, job == null ? null : job.tenantId());
        return ingestTaskDao.insert(mapper.toIngestTaskPo(job));
    }

    @Override
    public int updateIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision) {
        requireTenant(tenantId, job == null ? null : job.tenantId());
        requireRevision(expectedRevision);
        return ingestTaskDao.updateByTenantAndRevision(tenantId, mapper.toIngestTaskPo(job), expectedRevision);
    }

    @Override
    public List<RagIngestJobCandidate> listDueIngestJobCandidates(Instant now, int limit) {
        if (now == null) throw new IllegalArgumentException("now不能为空");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit必须在1到1000之间");
        return ingestTaskDao.queryDueCandidates(LocalDateTime.ofInstant(now, ZoneOffset.UTC), limit).stream()
                .map(this::toIngestCandidate).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<RagIngestJobEntity> claimDueIngestJob(String tenantId, String jobId, String leaseOwner,
                                                          Instant now, Instant leaseUntil) {
        requireText(tenantId, "tenantId");
        requireText(jobId, "jobId");
        requireText(leaseOwner, "leaseOwner");
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("RAG 任务领取时间非法");
        }
        int changed = ingestTaskDao.claimDue(tenantId, jobId, leaseOwner,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC));
        if (changed != 1) return Optional.empty();
        return Optional.ofNullable(mapper.toIngestJob(
                ingestTaskDao.queryByTenantAndTaskId(tenantId, jobId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<RagIngestJobEntity> claimCancelledIngestJobForCleanup(String tenantId, String jobId,
                                                                          String leaseOwner, Instant now,
                                                                          Instant leaseUntil) {
        validateClaimArguments(tenantId, jobId, leaseOwner, now, leaseUntil);
        int changed = ingestTaskDao.claimCancelledForCleanup(tenantId, jobId, leaseOwner,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC));
        if (changed != 1) return Optional.empty();
        return Optional.ofNullable(mapper.toIngestJob(
                ingestTaskDao.queryByTenantAndTaskId(tenantId, jobId)));
    }

    @Override
    public int updateClaimedIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision,
                                      String leaseOwner, long expectedFencingToken, Instant now) {
        validateWorkerUpdate(tenantId, job, expectedRevision, leaseOwner, expectedFencingToken, now);
        return ingestTaskDao.updateClaimedByTenantFenceAndRevision(tenantId, mapper.toIngestTaskPo(job),
                expectedRevision, leaseOwner, expectedFencingToken,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    @Override
    public int heartbeatClaimedIngestJob(String tenantId, String jobId, String leaseOwner,
                                         long expectedFencingToken, Instant now, Instant leaseUntil) {
        validateClaimArguments(tenantId, jobId, leaseOwner, now, leaseUntil);
        if (expectedFencingToken < 1) throw new IllegalArgumentException("fencing token必须为正数");
        return ingestTaskDao.heartbeatClaimed(tenantId, jobId, leaseOwner, expectedFencingToken,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeClaimedIngestJob(String tenantId, RagIngestJobEntity completedJob,
                                         long expectedTaskRevision, String leaseOwner,
                                         long expectedFencingToken, RagIndexActivation activation, Instant now) {
        validateLifecycle(tenantId, completedJob, expectedTaskRevision, leaseOwner,
                expectedFencingToken, now, RagIngestJobStatus.COMPLETED);
        requireActivationMatchesJob(activation, completedJob);
        LocalDateTime indexedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        requireChanged(documentVersionDao.markReadyByTenantAndRevision(tenantId,
                activation.knowledgeBaseId(), activation.documentId(), activation.versionId(),
                activation.generation(), activation.expectedVersionRevision(), indexedAt));
        requireChanged(documentDao.activateVersionByTenantAndRevision(tenantId,
                activation.knowledgeBaseId(), activation.documentId(), activation.versionId(),
                activation.generation(), activation.expectedDocumentRevision(), indexedAt));
        requireChanged(knowledgeBaseDao.activateGenerationByTenantAndRevision(tenantId,
                activation.knowledgeBaseId(), activation.generation(),
                activation.expectedKnowledgeBaseRevision()));
        requireChanged(ingestTaskDao.updateClaimedByTenantFenceAndRevision(tenantId,
                mapper.toIngestTaskPo(completedJob), expectedTaskRevision, leaseOwner,
                expectedFencingToken, indexedAt));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelClaimedIngestJob(String tenantId, RagIngestJobEntity cancelledJob,
                                       long expectedTaskRevision, long expectedVersionRevision,
                                       long expectedDocumentRevision, String leaseOwner,
                                       long expectedFencingToken, Instant now) {
        validateLifecycle(tenantId, cancelledJob, expectedTaskRevision, leaseOwner,
                expectedFencingToken, now, RagIngestJobStatus.CANCELLED);
        closeVersionAndDocument(tenantId, cancelledJob, expectedVersionRevision,
                expectedDocumentRevision, "cancelled");
        requireChanged(ingestTaskDao.cancelClaimedByTenantFenceAndRevision(tenantId,
                mapper.toIngestTaskPo(cancelledJob), expectedTaskRevision, leaseOwner, expectedFencingToken));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelUnclaimedIngestJob(String tenantId, RagIngestJobEntity cancelledJob,
                                         long expectedTaskRevision, long expectedVersionRevision,
                                         long expectedDocumentRevision) {
        requireTenant(tenantId, cancelledJob == null ? null : cancelledJob.tenantId());
        requireRevision(expectedTaskRevision);
        if (cancelledJob.status() != RagIngestJobStatus.CANCELLED || cancelledJob.lease() != null) {
            throw new IllegalArgumentException("无租约取消事务只允许关闭 cancelled 且未持有租约的任务");
        }
        closeVersionAndDocument(tenantId, cancelledJob, expectedVersionRevision,
                expectedDocumentRevision, "cancelled");
        requireChanged(ingestTaskDao.updateByTenantAndRevision(tenantId,
                mapper.toIngestTaskPo(cancelledJob), expectedTaskRevision));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void failClaimedIngestJob(String tenantId, RagIngestJobEntity failedJob,
                                     long expectedTaskRevision, long expectedVersionRevision,
                                     long expectedDocumentRevision, String leaseOwner,
                                     long expectedFencingToken, Instant now) {
        if (failedJob == null || failedJob.status() != RagIngestJobStatus.FAILED
                && failedJob.status() != RagIngestJobStatus.DEAD) {
            throw new IllegalArgumentException("失败事务只允许关闭 failed/dead 任务");
        }
        validateLifecycle(tenantId, failedJob, expectedTaskRevision, leaseOwner,
                expectedFencingToken, now, failedJob.status());
        closeVersionAndDocument(tenantId, failedJob, expectedVersionRevision,
                expectedDocumentRevision, "failed");
        requireChanged(ingestTaskDao.updateClaimedByTenantFenceAndRevision(tenantId,
                mapper.toIngestTaskPo(failedJob), expectedTaskRevision, leaseOwner,
                expectedFencingToken, LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    @Override
    public List<RagChunkEntity> listChunks(String tenantId, String versionId) {
        return chunkDao.queryListByTenantAndVersionId(requireText(tenantId, "tenantId"),
                        requireText(versionId, "versionId")).stream().map(mapper::toChunk).toList();
    }

    @Override
    public List<RagChunkEntity> listChunksByIds(String tenantId, List<String> chunkIds) {
        requireText(tenantId, "tenantId");
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        List<String> normalized = chunkIds.stream().map(value -> requireText(value, "chunkId"))
                .distinct().toList();
        if (normalized.size() > 500) {
            throw new IllegalArgumentException("RAG分块批量查询不能超过500条");
        }
        return chunkDao.queryListByTenantAndChunkIds(tenantId, normalized).stream()
                .map(mapper::toChunk).toList();
    }

    @Override
    public int upsertChunks(String tenantId, String versionId, List<RagChunkEntity> chunks) {
        requireText(tenantId, "tenantId");
        requireText(versionId, "versionId");
        if (chunks == null || chunks.isEmpty()) return 0;
        List<RagChunkPO> records = chunks.stream().map(chunk -> {
            requireTenant(tenantId, chunk == null ? null : chunk.tenantId());
            if (!versionId.equals(chunk.versionId())) {
                throw new IllegalArgumentException("RAG 切片版本范围不一致");
            }
            return mapper.toChunkPo(chunk);
        }).toList();
        return chunkDao.upsertBatch(tenantId, versionId, records);
    }

    @Override
    public int deleteChunks(String tenantId, String versionId) {
        return chunkDao.softDeleteByTenantAndVersionId(requireText(tenantId, "tenantId"),
                requireText(versionId, "versionId"));
    }

    @Override
    public Optional<RagRetrievalProfileEntity> findRetrievalProfile(String tenantId, String profileId) {
        return Optional.ofNullable(mapper.toRetrievalProfile(retrievalProfileDao.queryByTenantAndProfileId(
                requireText(tenantId, "tenantId"), requireText(profileId, "profileId"))));
    }

    @Override
    public List<RagRetrievalProfileEntity> listRetrievalProfiles(String tenantId) {
        return retrievalProfileDao.queryListByTenant(requireText(tenantId, "tenantId")).stream()
                .map(mapper::toRetrievalProfile).toList();
    }

    @Override
    public int insertRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile) {
        requireTenant(tenantId, profile == null ? null : profile.tenantId());
        try {
            return retrievalProfileDao.insert(mapper.toRetrievalProfilePo(profile));
        } catch (DuplicateKeyException exception) {
            throw new AppException("RAG_PROFILE_CONFLICT", "检索策略创建冲突", exception);
        }
    }

    @Override
    public int updateRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile,
                                      long expectedRevision) {
        requireTenant(tenantId, profile == null ? null : profile.tenantId());
        if (expectedRevision < 0 || profile.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("RAG检索配置revision非法");
        }
        return retrievalProfileDao.updateByTenantAndRevision(tenantId,
                mapper.toRetrievalProfilePo(profile), expectedRevision);
    }

    @Override
    public List<RagAgentBindingEntity> listBindings(String tenantId, RagBindingTargetType targetType,
                                                     String targetId) {
        if (targetType == null) throw new IllegalArgumentException("targetType不能为空");
        return agentBindingDao.queryActiveByTenantAndTarget(requireText(tenantId, "tenantId"),
                        codec.databaseValue(targetType), requireText(targetId, "targetId")).stream()
                .map(mapper::toAgentBinding).toList();
    }

    @Override
    public List<RagAgentBindingEntity> listBindings(String tenantId) {
        return agentBindingDao.queryListByTenant(requireText(tenantId, "tenantId")).stream()
                .map(mapper::toAgentBinding).toList();
    }

    @Override
    public Optional<RagAgentBindingEntity> findBinding(String tenantId, String bindingId) {
        return Optional.ofNullable(mapper.toAgentBinding(agentBindingDao.queryByTenantAndBindingId(
                requireText(tenantId, "tenantId"), requireText(bindingId, "bindingId"))));
    }

    @Override
    public int insertBinding(String tenantId, RagAgentBindingEntity binding) {
        requireTenant(tenantId, binding == null ? null : binding.tenantId());
        try {
            return agentBindingDao.insert(mapper.toAgentBindingPo(binding));
        } catch (DuplicateKeyException exception) {
            throw new AppException("RAG_BINDING_CONFLICT", "当前目标已绑定该知识库", exception);
        }
    }

    @Override
    public int deleteBinding(String tenantId, String bindingId, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("RAG绑定revision非法");
        return agentBindingDao.softDeleteByTenantAndRevision(requireText(tenantId, "tenantId"),
                requireText(bindingId, "bindingId"), expectedRevision);
    }

    private void requireTenant(String trustedTenantId, String entityTenantId) {
        requireText(trustedTenantId, "tenantId");
        if (!trustedTenantId.equals(entityTenantId)) {
            throw new IllegalArgumentException("RAG 实体租户范围不一致");
        }
    }

    private RagIngestJobCandidate toIngestCandidate(RagIngestCandidatePO candidate) {
        if (candidate == null) throw new IllegalStateException("RAG 任务候选投影不能为空");
        return new RagIngestJobCandidate(candidate.getTenantId(), candidate.getJobId());
    }

    private void validateClaimArguments(String tenantId, String jobId, String leaseOwner,
                                        Instant now, Instant leaseUntil) {
        requireText(tenantId, "tenantId");
        requireText(jobId, "jobId");
        requireText(leaseOwner, "leaseOwner");
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("RAG 任务领取时间非法");
        }
    }

    private void validateWorkerUpdate(String tenantId, RagIngestJobEntity job, long expectedRevision,
                                      String leaseOwner, long expectedFencingToken, Instant now) {
        requireTenant(tenantId, job == null ? null : job.tenantId());
        requireRevision(expectedRevision);
        requireText(leaseOwner, "leaseOwner");
        if (expectedFencingToken < 1 || now == null) {
            throw new IllegalArgumentException("Worker fencing token或时间非法");
        }
        if (job.fencingToken() != expectedFencingToken) {
            throw new IllegalArgumentException("Worker 任务 fencing token与预期不一致");
        }
    }

    private void validateLifecycle(String tenantId, RagIngestJobEntity job, long expectedRevision,
                                   String leaseOwner, long expectedFencingToken, Instant now,
                                   RagIngestJobStatus expectedStatus) {
        validateWorkerUpdate(tenantId, job, expectedRevision, leaseOwner, expectedFencingToken, now);
        if (job.status() != expectedStatus) {
            throw new IllegalArgumentException("RAG lifecycle 任务目标状态不一致");
        }
    }

    private void requireActivationMatchesJob(RagIndexActivation activation, RagIngestJobEntity job) {
        if (activation == null || !activation.knowledgeBaseId().equals(job.knowledgeBaseId())
                || !activation.documentId().equals(job.documentId())
                || !activation.versionId().equals(job.versionId())
                || activation.generation() != job.generation()) {
            throw new IllegalArgumentException("RAG 激活范围与任务不一致");
        }
    }

    private void closeVersionAndDocument(String tenantId, RagIngestJobEntity job,
                                         long expectedVersionRevision, long expectedDocumentRevision,
                                         String versionStatus) {
        requireRevision(expectedVersionRevision);
        requireRevision(expectedDocumentRevision);
        requireChanged(documentVersionDao.closeByTenantAndRevision(tenantId, job.knowledgeBaseId(),
                job.documentId(), job.versionId(), job.generation(), versionStatus, expectedVersionRevision));
        requireChanged(documentDao.closeTargetGenerationByTenantAndRevision(tenantId, job.knowledgeBaseId(),
                job.documentId(), job.generation(), expectedDocumentRevision));
    }

    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new AppException("RAG_LIFECYCLE_CONFLICT",
                    "RAG 状态已被其他 Worker 或管理操作修改，本次事务已回滚");
        }
    }

    private void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("expectedRevision不能为负数");
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value;
    }
}
