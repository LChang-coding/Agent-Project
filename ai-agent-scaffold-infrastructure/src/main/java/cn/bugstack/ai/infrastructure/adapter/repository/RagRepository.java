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
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagObjectStorageScope;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobCandidate;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.RagChunkPO;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 MySQL 强租户条件、乐观锁和任务 fencing 的 RAG 仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class RagRepository implements IRagRepository {

    /** 知识库聚合的查询、锁定和代次切换入口。 */
    private final IRagKnowledgeBaseDao knowledgeBaseDao;
    /** 逻辑文档状态和当前活动版本的持久化入口。 */
    private final IRagDocumentDao documentDao;
    /** 不可变文档版本及解析产物信息的持久化入口。 */
    private final IRagDocumentVersionDao documentVersionDao;
    /** 摄取、删除任务的领取、续租和状态更新入口。 */
    private final IRagIngestTaskDao ingestTaskDao;
    /** 检索分块的批量写入、查询和清理入口。 */
    private final IRagChunkDao chunkDao;
    /** 租户检索参数配置的持久化入口。 */
    private final IRagRetrievalProfileDao retrievalProfileDao;
    /** Agent、工作流与知识库绑定关系的持久化入口。 */
    private final IRagAgentBindingDao agentBindingDao;
    /** 在数据库对象与领域实体之间转换，并恢复持久化默认值。 */
    private final RagPersistenceMapper mapper;
    /** 将枚举、检查点和元数据转换为稳定的数据库表示。 */
    private final RagPersistenceCodec codec;

    @Override
    /** 查询租户知识库聚合根。 */
    public Optional<RagKnowledgeBaseEntity> findKnowledgeBase(String tenantId, String knowledgeBaseId) {
        return Optional.ofNullable(mapper.toKnowledgeBase(
                knowledgeBaseDao.queryByTenantAndKnowledgeBaseId(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))));
    }

    @Override
    /** 查询租户知识库列表。 */
    public List<RagKnowledgeBaseEntity> listKnowledgeBases(String tenantId) {
        return knowledgeBaseDao.queryListByTenantId(requireText(tenantId, "tenantId")).stream()
                .map(mapper::toKnowledgeBase).toList();
    }

    @Override
    /** 校验实体租户后新增知识库。 */
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
    /** 以期望 revision 更新知识库聚合根。 */
    public int updateKnowledgeBase(String tenantId, RagKnowledgeBaseEntity knowledgeBase, long expectedRevision) {
        requireTenant(tenantId, knowledgeBase == null ? null : knowledgeBase.tenantId());
        requireRevision(expectedRevision);
        return knowledgeBaseDao.updateByTenantAndRevision(tenantId,
                mapper.toKnowledgeBasePo(knowledgeBase), expectedRevision);
    }

    @Override
    /** 按稳定逻辑文档 ID 查询。 */
    public Optional<RagDocumentEntity> findDocument(String tenantId, String documentId) {
        return Optional.ofNullable(mapper.toDocument(documentDao.queryByTenantAndDocumentId(
                requireText(tenantId, "tenantId"), requireText(documentId, "documentId"))));
    }

    @Override
    /** 按文档 ID 批量查询并去重；限制单批数量，避免生成过大的 SQL 条件。 */
    public List<RagDocumentEntity> listDocumentsByIds(String tenantId, List<String> documentIds) {
        requireText(tenantId, "tenantId");
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        List<String> normalized = documentIds.stream().map(value -> requireText(value, "documentId"))
                .distinct().toList();
        if (normalized.size() > 500) {
            throw new IllegalArgumentException("RAG文档批量查询不能超过500条");
        }
        return documentDao.queryListByTenantAndDocumentIds(tenantId, normalized).stream()
                .map(mapper::toDocument).toList();
    }

    @Override
    /** 查询知识库下的全部逻辑文档。 */
    public List<RagDocumentEntity> listDocuments(String tenantId, String knowledgeBaseId) {
        return documentDao.queryListByTenantAndKnowledgeBaseId(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId")).stream()
                .map(mapper::toDocument).toList();
    }

    @Override
    /** 校验可信租户后新增逻辑文档。 */
    public int insertDocument(String tenantId, RagDocumentEntity document) {
        requireTenant(tenantId, document == null ? null : document.tenantId());
        return documentDao.insert(mapper.toDocumentPo(document));
    }

    @Override
    /** 仅在 revision 未变化时更新文档状态和活动版本。 */
    public int updateDocument(String tenantId, RagDocumentEntity document, long expectedRevision) {
        requireTenant(tenantId, document == null ? null : document.tenantId());
        requireRevision(expectedRevision);
        return documentDao.updateByTenantAndRevision(tenantId, mapper.toDocumentPo(document), expectedRevision);
    }

    @Override
    /** 查询不可变文档版本。 */
    public Optional<RagDocumentVersionEntity> findDocumentVersion(String tenantId, String versionId) {
        return Optional.ofNullable(mapper.toDocumentVersion(documentVersionDao.queryByTenantAndVersionId(
                requireText(tenantId, "tenantId"), requireText(versionId, "versionId"))));
    }

    @Override
    /** 按逻辑文档查询其所有不可变版本。 */
    public List<RagDocumentVersionEntity> listDocumentVersions(String tenantId, String documentId) {
        return documentVersionDao.queryListByTenantAndDocumentId(requireText(tenantId, "tenantId"),
                        requireText(documentId, "documentId")).stream()
                .map(mapper::toDocumentVersion).toList();
    }

    @Override
    /** 仅在 revision 未变化时更新版本状态和解析产物位置。 */
    public int updateDocumentVersion(String tenantId, RagDocumentVersionEntity version, long expectedRevision) {
        requireTenant(tenantId, version == null ? null : version.tenantId());
        requireRevision(expectedRevision);
        return documentVersionDao.updateByTenantAndRevision(tenantId,
                mapper.toDocumentVersionPo(version), expectedRevision);
    }

    @Override
    /** 按任务 ID 查询摄取账本。 */
    public Optional<RagIngestJobEntity> findIngestJob(String tenantId, String jobId) {
        return Optional.ofNullable(mapper.toIngestJob(ingestTaskDao.queryByTenantAndTaskId(
                requireText(tenantId, "tenantId"), requireText(jobId, "jobId"))));
    }

    @Override
    /** 查询知识库最近的摄取与删除任务，并限制返回数量。 */
    public List<RagIngestJobEntity> listIngestJobs(String tenantId, String knowledgeBaseId, int limit) {
        requireText(tenantId, "tenantId");
        requireText(knowledgeBaseId, "knowledgeBaseId");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("RAG摄取任务查询数量必须在1到200之间");
        }
        return ingestTaskDao.queryListByTenantAndKnowledgeBaseId(tenantId, knowledgeBaseId, limit).stream()
                .map(mapper::toIngestJob).toList();
    }

    @Override
    /** 按幂等键查询既有任务，用于重复上传或重复删除时重放原结果。 */
    public Optional<RagIngestJobEntity> findIngestJobByIdempotencyKey(String tenantId, String idempotencyKey) {
        return Optional.ofNullable(mapper.toIngestJob(ingestTaskDao.queryByTenantAndTaskKey(
                requireText(tenantId, "tenantId"), requireText(idempotencyKey, "idempotencyKey"))));
    }

    @Override
    /** 校验可信租户后新增任务账本。 */
    public int insertIngestJob(String tenantId, RagIngestJobEntity job) {
        requireTenant(tenantId, job == null ? null : job.tenantId());
        return ingestTaskDao.insert(mapper.toIngestTaskPo(job));
    }

    @Override
    /** 使用 revision 乐观锁更新未领取任务，避免覆盖并发状态变化。 */
    public int updateIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision) {
        requireTenant(tenantId, job == null ? null : job.tenantId());
        requireRevision(expectedRevision);
        return ingestTaskDao.updateByTenantAndRevision(tenantId, mapper.toIngestTaskPo(job), expectedRevision);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 只允许 failed 任务以新 taskId/taskKey 重排，并保留原失败账本。 */
    public void requeueFailedIngestJob(String tenantId, RagIngestJobEntity requeuedJob,
                                       long expectedTaskRevision, RagDocumentVersionEntity queuedVersion,
                                       long expectedVersionRevision, RagDocumentEntity processingDocument,
                                       long expectedDocumentRevision, long expectedKnowledgeBaseRevision) {
        requireTenant(tenantId, requeuedJob == null ? null : requeuedJob.tenantId());
        requireTenant(tenantId, queuedVersion == null ? null : queuedVersion.tenantId());
        requireTenant(tenantId, processingDocument == null ? null : processingDocument.tenantId());
        requireRevision(expectedTaskRevision);
        requireRevision(expectedVersionRevision);
        requireRevision(expectedDocumentRevision);
        requireRevision(expectedKnowledgeBaseRevision);
        if (requeuedJob.operation() != RagIngestOperation.INGEST
                || requeuedJob.status() != RagIngestJobStatus.PENDING
                || queuedVersion.status() != RagDocumentVersionStatus.QUEUED
                || processingDocument.status() != RagDocumentStatus.PROCESSING
                || !requeuedJob.knowledgeBaseId().equals(queuedVersion.knowledgeBaseId())
                || !requeuedJob.knowledgeBaseId().equals(processingDocument.knowledgeBaseId())
                || !requeuedJob.documentId().equals(queuedVersion.documentId())
                || !requeuedJob.documentId().equals(processingDocument.documentId())
                || !requeuedJob.versionId().equals(queuedVersion.versionId())
                || requeuedJob.generation() != queuedVersion.generation()
                || processingDocument.targetGeneration() == null
                || processingDocument.targetGeneration() != requeuedJob.generation()) {
            throw new IllegalArgumentException("失败摄取恢复范围非法");
        }
        RagKnowledgeBaseEntity lockedKnowledgeBase = mapper.toKnowledgeBase(
                knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate(
                        tenantId, requeuedJob.knowledgeBaseId()));
        if (lockedKnowledgeBase == null || !lockedKnowledgeBase.status().searchable()
                || lockedKnowledgeBase.revision() != expectedKnowledgeBaseRevision) {
            throw new AppException("RAG_INGEST_RETRY_KNOWLEDGE_BASE_CHANGED",
                    "知识库状态已变化，请刷新后重试");
        }
        requireChanged(documentVersionDao.updateByTenantAndRevision(tenantId,
                mapper.toDocumentVersionPo(queuedVersion), expectedVersionRevision));
        requireChanged(documentDao.updateByTenantAndRevision(tenantId,
                mapper.toDocumentPo(processingDocument), expectedDocumentRevision));
        requireChanged(ingestTaskDao.updateByTenantAndRevision(tenantId,
                mapper.toIngestTaskPo(requeuedJob), expectedTaskRevision));
    }

    @Override
    /** 全局扫描最小候选身份；扫描结果不授予执行权。 */
    public List<RagIngestJobCandidate> listDueIngestJobCandidates(Instant now, int limit) {
        if (now == null) throw new IllegalArgumentException("now不能为空");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit必须在1到1000之间");
        return ingestTaskDao.queryDueCandidates(LocalDateTime.ofInstant(now, ZoneOffset.UTC), limit).stream()
                .map(this::toIngestCandidate).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 原子领取到期任务并回读含新租约、围栏与修订号的快照。 */
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
    /** 为 cancel_requested 任务领取专用清理租约。 */
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
    /** Worker 更新必须同时匹配租户、租约、围栏与 revision。 */
    public int updateClaimedIngestJob(String tenantId, RagIngestJobEntity job, long expectedRevision,
                                      String leaseOwner, long expectedFencingToken, Instant now) {
        validateWorkerUpdate(tenantId, job, expectedRevision, leaseOwner, expectedFencingToken, now);
        return ingestTaskDao.updateClaimedByTenantFenceAndRevision(tenantId, mapper.toIngestTaskPo(job),
                expectedRevision, leaseOwner, expectedFencingToken,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    @Override
    /** 心跳只续租，不争用 rowVersion。 */
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
    /** 原子激活版本、切换文档 generation 并闭合摄取任务。 */
    public void completeClaimedIngestJob(String tenantId, RagIngestJobEntity completedJob,
                                         long expectedTaskRevision, String leaseOwner,
                                         long expectedFencingToken, RagIndexActivation activation, Instant now) {
        validateLifecycle(tenantId, completedJob, expectedTaskRevision, leaseOwner,
                expectedFencingToken, now, RagIngestJobStatus.COMPLETED);
        requireActivationMatchesJob(activation, completedJob);
        LocalDateTime indexedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        requireChanged(documentVersionDao.markReadyByTenantAndRevision(tenantId,
                activation.knowledgeBaseId(), activation.documentId(), activation.versionId(),
                activation.generation(), activation.expectedVersionRevision(), activation.pageCount(),
                activation.characterCount(), activation.chunkCount(), activation.parsedObjectBucket(),
                activation.parsedObjectKey(), activation.parsedContentHash(), activation.parsedSizeBytes(),
                codec.writeMetadata(activationMetadata(activation)),
                indexedAt));
        requireChanged(documentDao.activateVersionByTenantAndRevision(tenantId,
                activation.knowledgeBaseId(), activation.documentId(), activation.versionId(),
                activation.generation(), activation.expectedDocumentRevision(), activation.pageCount(),
                activation.chunkCount(), indexedAt));
        requireChanged(knowledgeBaseDao.activateGenerationByTenantAndRevision(tenantId,
                activation.knowledgeBaseId(), activation.generation(),
                activation.expectedKnowledgeBaseRevision()));
        requireChanged(ingestTaskDao.updateClaimedByTenantFenceAndRevision(tenantId,
                mapper.toIngestTaskPo(completedJob), expectedTaskRevision, leaseOwner,
                expectedFencingToken, indexedAt));
    }

    /** 提取需要随就绪版本长期保存的解析器、质量和清单元数据。 */
    private Map<String, String> activationMetadata(RagIndexActivation activation) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("parsedContentHash", activation.parsedContentHash());
        result.put("parsedSizeBytes", Long.toString(activation.parsedSizeBytes()));
        if (activation.parserName() != null) result.put("parserName", activation.parserName());
        if (activation.parserRevision() != null) result.put("parserRevision", activation.parserRevision());
        if (activation.irSchemaVersion() != null) result.put("irSchemaVersion", activation.irSchemaVersion());
        if (activation.qualityDisposition() != null) {
            result.put("qualityDisposition", activation.qualityDisposition());
            result.put("qualityScore", Double.toString(activation.qualityScore()));
        }
        if (activation.qualityReportObjectKey() != null) {
            result.put("qualityReportObjectKey", activation.qualityReportObjectKey());
        }
        if (activation.chunkManifestObjectKey() != null) {
            result.put("chunkManifestObjectKey", activation.chunkManifestObjectKey());
        }
        if (activation.tokenizerVersion() != null) {
            result.put("tokenizerVersion", activation.tokenizerVersion());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 删除完成必须同时关闭分块、版本、文档墓碑和任务。 */
    public void completeClaimedDeleteJob(String tenantId, RagIngestJobEntity completedJob,
                                         long expectedTaskRevision, String leaseOwner,
                                         long expectedFencingToken, RagDocumentEntity deletedDocument,
                                         List<RagDocumentVersionEntity> deletedVersions, Instant now) {
        validateLifecycle(tenantId, completedJob, expectedTaskRevision, leaseOwner,
                expectedFencingToken, now, RagIngestJobStatus.COMPLETED);
        if (completedJob.operation() != RagIngestOperation.DELETE
                || deletedDocument == null || deletedVersions == null || deletedVersions.isEmpty()
                || !tenantId.equals(deletedDocument.tenantId())
                || !completedJob.documentId().equals(deletedDocument.documentId())
                || !completedJob.knowledgeBaseId().equals(deletedDocument.knowledgeBaseId())) {
            throw new IllegalArgumentException("删除完成事务范围非法");
        }
        RagDocumentPO lockedDocumentPo = documentDao.queryByTenantKnowledgeBaseAndDocumentIdForUpdate(
                tenantId, deletedDocument.knowledgeBaseId(), deletedDocument.documentId());
        RagDocumentEntity lockedDocument = mapper.toDocument(lockedDocumentPo);
        if (lockedDocument == null || lockedDocument.status() != RagDocumentStatus.DELETING
                || lockedDocument.revision() != deletedDocument.revision() - 1) {
            throw new AppException("RAG_DELETE_DOCUMENT_SET_CHANGED", "删除收口时文档墓碑已变化");
        }
        List<RagDocumentVersionEntity> lockedVersions = documentVersionDao
                .queryListByTenantAndDocumentIdForUpdate(tenantId, deletedDocument.documentId())
                .stream().map(mapper::toDocumentVersion).toList();
        Map<String, RagDocumentVersionEntity> targetsById = new LinkedHashMap<>();
        for (RagDocumentVersionEntity target : deletedVersions) {
            if (targetsById.putIfAbsent(target.versionId(), target) != null) {
                throw new IllegalArgumentException("删除完成事务版本重复");
            }
        }
        if (lockedVersions.size() != targetsById.size()) {
            throw new AppException("RAG_DELETE_VERSION_SET_CHANGED", "删除收口时文档版本集合已变化");
        }
        for (RagDocumentVersionEntity lockedVersion : lockedVersions) {
            RagDocumentVersionEntity version = targetsById.get(lockedVersion.versionId());
            if (version == null || !tenantId.equals(version.tenantId())
                    || !deletedDocument.documentId().equals(version.documentId())
                    || !deletedDocument.knowledgeBaseId().equals(version.knowledgeBaseId())
                    || version.status() != RagDocumentVersionStatus.DELETED
                    || lockedVersion.status() != RagDocumentVersionStatus.DELETING
                    || lockedVersion.revision() != version.revision() - 1
                    || lockedVersion.versionNumber() != version.versionNumber()
                    || lockedVersion.generation() != version.generation()) {
                throw new IllegalArgumentException("删除完成事务版本范围非法");
            }
            requireChanged(documentVersionDao.markDeletedByTenantAndRevision(tenantId,
                    version.knowledgeBaseId(), version.documentId(), version.versionId(), version.revision() - 1));
        }
        requireChanged(documentDao.markDeletedByTenantAndRevision(tenantId,
                deletedDocument.knowledgeBaseId(), deletedDocument.documentId(),
                deletedDocument.revision() - 1));
        requireChanged(ingestTaskDao.updateClaimedByTenantFenceAndRevision(tenantId,
                mapper.toIngestTaskPo(completedJob), expectedTaskRevision, leaseOwner,
                expectedFencingToken, LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 取消完成先清理本代次产物，再以当前租约闭合任务。 */
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
    /** 未领取任务取消只允许通过期望 revision 推进。 */
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
    /** 失败任务关闭目标版本和 generation，保留既有活动版本。 */
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
    @Transactional(rollbackFor = Exception.class)
    /** 失败补偿清理完成后，从 cancel_requested 前置态原子收口为 failed/dead。 */
    public void failAfterCleanupClaimedIngestJob(String tenantId, RagIngestJobEntity failedJob,
                                                 long expectedTaskRevision, long expectedVersionRevision,
                                                 long expectedDocumentRevision, String leaseOwner,
                                                 long expectedFencingToken, Instant now) {
        if (failedJob == null || failedJob.status() != RagIngestJobStatus.FAILED
                && failedJob.status() != RagIngestJobStatus.DEAD) {
            throw new IllegalArgumentException("失败清理收口只允许 failed/dead 任务");
        }
        validateLifecycle(tenantId, failedJob, expectedTaskRevision, leaseOwner,
                expectedFencingToken, now, failedJob.status());
        closeVersionAndDocument(tenantId, failedJob, expectedVersionRevision,
                expectedDocumentRevision, "failed");
        requireChanged(ingestTaskDao.cancelClaimedByTenantFenceAndRevision(tenantId,
                mapper.toIngestTaskPo(failedJob), expectedTaskRevision, leaseOwner,
                expectedFencingToken));
    }

    @Override
    /** 查询指定版本的全部未删除分块。 */
    public List<RagChunkEntity> listChunks(String tenantId, String versionId) {
        return chunkDao.queryListByTenantAndVersionId(requireText(tenantId, "tenantId"),
                        requireText(versionId, "versionId")).stream().map(mapper::toChunk).toList();
    }

    @Override
    /** 按分块 ID 批量查询并去重；限制单批数量，避免生成过大的 SQL 条件。 */
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
    /** 批量写分块前校验每个实体租户与 versionId。 */
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
    /** 软删除指定版本的分块，使正常检索立即不可见但保留审计数据。 */
    public int deleteChunks(String tenantId, String versionId) {
        return chunkDao.softDeleteByTenantAndVersionId(requireText(tenantId, "tenantId"),
                requireText(versionId, "versionId"));
    }

    @Override
    /** 物理删除指定版本的分块，用于已经确认可清理的任务数据。 */
    public int purgeChunks(String tenantId, String versionId) {
        return chunkDao.deleteByTenantAndVersionId(requireText(tenantId, "tenantId"),
                requireText(versionId, "versionId"));
    }

    @Override
    /** 统计指定版本的全部分块，包括已经软删除的数据。 */
    public long countAllChunks(String tenantId, String versionId) {
        return chunkDao.countAllByTenantAndVersionId(requireText(tenantId, "tenantId"),
                requireText(versionId, "versionId"));
    }

    @Override
    /** 查询租户检索策略。 */
    public Optional<RagRetrievalProfileEntity> findRetrievalProfile(String tenantId, String profileId) {
        return Optional.ofNullable(mapper.toRetrievalProfile(retrievalProfileDao.queryByTenantAndProfileId(
                requireText(tenantId, "tenantId"), requireText(profileId, "profileId"))));
    }

    @Override
    /** 查询租户维护的全部检索策略。 */
    public List<RagRetrievalProfileEntity> listRetrievalProfiles(String tenantId) {
        return retrievalProfileDao.queryListByTenant(requireText(tenantId, "tenantId")).stream()
                .map(mapper::toRetrievalProfile).toList();
    }

    @Override
    /** 新增检索策略，并把唯一键冲突转换为稳定业务错误。 */
    public int insertRetrievalProfile(String tenantId, RagRetrievalProfileEntity profile) {
        requireTenant(tenantId, profile == null ? null : profile.tenantId());
        try {
            return retrievalProfileDao.insert(mapper.toRetrievalProfilePo(profile));
        } catch (DuplicateKeyException exception) {
            throw new AppException("RAG_PROFILE_CONFLICT", "检索策略创建冲突", exception);
        }
    }

    @Override
    /** 校验领域 revision 连续递增后，以乐观锁更新检索策略。 */
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
    /** 查询目标生效绑定，并按优先级返回。 */
    public List<RagAgentBindingEntity> listBindings(String tenantId, RagBindingTargetType targetType,
                                                     String targetId) {
        if (targetType == null) throw new IllegalArgumentException("targetType不能为空");
        return agentBindingDao.queryActiveByTenantAndTarget(requireText(tenantId, "tenantId"),
                        codec.databaseValue(targetType), requireText(targetId, "targetId")).stream()
                .map(mapper::toAgentBinding).toList();
    }

    @Override
    /** 查询租户下的全部知识库绑定，包括不同目标类型。 */
    public List<RagAgentBindingEntity> listBindings(String tenantId) {
        return agentBindingDao.queryListByTenant(requireText(tenantId, "tenantId")).stream()
                .map(mapper::toAgentBinding).toList();
    }

    @Override
    /** 按绑定 ID 查询租户范围内的绑定。 */
    public Optional<RagAgentBindingEntity> findBinding(String tenantId, String bindingId) {
        return Optional.ofNullable(mapper.toAgentBinding(agentBindingDao.queryByTenantAndBindingId(
                requireText(tenantId, "tenantId"), requireText(bindingId, "bindingId"))));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 锁定知识库确认仍可检索后新增绑定，并将重复绑定转换为业务冲突。 */
    public int insertBinding(String tenantId, RagAgentBindingEntity binding) {
        requireTenant(tenantId, binding == null ? null : binding.tenantId());
        RagKnowledgeBaseEntity lockedKnowledgeBase = mapper.toKnowledgeBase(
                knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate(
                        tenantId, binding.knowledgeBaseId()));
        if (lockedKnowledgeBase == null || !lockedKnowledgeBase.status().searchable()) {
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "知识库当前不可绑定");
        }
        try {
            return agentBindingDao.insert(mapper.toAgentBindingPo(binding));
        } catch (DuplicateKeyException exception) {
            throw new AppException("RAG_BINDING_CONFLICT", "当前目标已绑定该知识库", exception);
        }
    }

    @Override
    /** 使用 revision 软删除绑定，防止覆盖并发编辑。 */
    public int deleteBinding(String tenantId, String bindingId, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("RAG绑定revision非法");
        return agentBindingDao.softDeleteByTenantAndRevision(requireText(tenantId, "tenantId"),
                requireText(bindingId, "bindingId"), expectedRevision);
    }

    /** 所有写入都拒绝可信租户与实体租户不一致。 */
    private void requireTenant(String trustedTenantId, String entityTenantId) {
        requireText(trustedTenantId, "tenantId");
        if (!trustedTenantId.equals(entityTenantId)) {
            throw new IllegalArgumentException("RAG 实体租户范围不一致");
        }
    }

    /** 将全局扫描的最小投影转换为不携带执行权的任务候选。 */
    private RagIngestJobCandidate toIngestCandidate(RagIngestCandidatePO candidate) {
        if (candidate == null) throw new IllegalStateException("RAG 任务候选投影不能为空");
        return new RagIngestJobCandidate(candidate.getTenantId(), candidate.getJobId());
    }

    /** 校验领取范围和租约时间，拒绝空身份或非递增租约。 */
    private void validateClaimArguments(String tenantId, String jobId, String leaseOwner,
                                        Instant now, Instant leaseUntil) {
        requireText(tenantId, "tenantId");
        requireText(jobId, "jobId");
        requireText(leaseOwner, "leaseOwner");
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("RAG 任务领取时间非法");
        }
    }

    /** 校验 Worker 更新的任务身份、修订号、租约、围栏和阶段。 */
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

    /** 校验 Worker 仍持有执行权，并确认准备写入的任务终态符合调用语义。 */
    private void validateLifecycle(String tenantId, RagIngestJobEntity job, long expectedRevision,
                                   String leaseOwner, long expectedFencingToken, Instant now,
                                   RagIngestJobStatus expectedStatus) {
        validateWorkerUpdate(tenantId, job, expectedRevision, leaseOwner, expectedFencingToken, now);
        if (job.status() != expectedStatus) {
            throw new IllegalArgumentException("RAG lifecycle 任务目标状态不一致");
        }
    }

    /** 激活快照必须与任务的文档、版本和 generation 完全一致。 */
    private void requireActivationMatchesJob(RagIndexActivation activation, RagIngestJobEntity job) {
        if (activation == null || !activation.knowledgeBaseId().equals(job.knowledgeBaseId())
                || !activation.documentId().equals(job.documentId())
                || !activation.versionId().equals(job.versionId())
                || activation.generation() != job.generation()
                || activation.chunkCount() != job.checkpoint().totalChunks()
                || activation.pageCount() != job.checkpoint().pageCount()
                || activation.characterCount() != job.checkpoint().characterCount()
                || !RagObjectStorageScope.containsVersionObject(activation.parsedObjectKey(), job.tenantId(),
                job.knowledgeBaseId(), job.documentId(), job.versionId())) {
            throw new IllegalArgumentException("RAG 激活范围与任务不一致");
        }
    }

    /** 在同一事务中关闭目标版本和文档目标代次，保留此前已经激活的版本。 */
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

    /** 影响行数 0 一律视为并发冲突或租约失权。 */
    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new AppException("RAG_LIFECYCLE_CONFLICT",
                    "RAG 状态已被其他 Worker 或管理操作修改，本次事务已回滚");
        }
    }

    /** revision 必须来自已读取快照，负数不能参与乐观锁更新。 */
    private void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("expectedRevision不能为负数");
    }

    /** 统一拒绝持久化边界上的空标识，并返回已校验原值。 */
    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value;
    }
}
