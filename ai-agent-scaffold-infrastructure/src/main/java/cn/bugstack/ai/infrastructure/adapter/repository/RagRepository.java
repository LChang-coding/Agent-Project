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
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.RagChunkPO;
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
    public List<RagChunkEntity> listChunks(String tenantId, String versionId) {
        return chunkDao.queryListByTenantAndVersionId(requireText(tenantId, "tenantId"),
                        requireText(versionId, "versionId")).stream().map(mapper::toChunk).toList();
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
    public List<RagAgentBindingEntity> listBindings(String tenantId, RagBindingTargetType targetType,
                                                     String targetId) {
        if (targetType == null) throw new IllegalArgumentException("targetType不能为空");
        return agentBindingDao.queryActiveByTenantAndTarget(requireText(tenantId, "tenantId"),
                        codec.databaseValue(targetType), requireText(targetId, "targetId")).stream()
                .map(mapper::toAgentBinding).toList();
    }

    private void requireTenant(String trustedTenantId, String entityTenantId) {
        requireText(trustedTenantId, "tenantId");
        if (!trustedTenantId.equals(entityTenantId)) {
            throw new IllegalArgumentException("RAG 实体租户范围不一致");
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
