package cn.bugstack.ai.infrastructure.rag.persistence;

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
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagLease;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.dao.po.RagAgentBindingPO;
import cn.bugstack.ai.infrastructure.dao.po.RagChunkPO;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentVersionPO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalProfilePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * RAG 领域对象与 MyBatis PO 的集中映射器。
 */
@Component
@RequiredArgsConstructor
public class RagPersistenceMapper {

    private final RagPersistenceCodec codec;

    public RagKnowledgeBaseEntity toKnowledgeBase(RagKnowledgeBasePO po) {
        if (po == null) return null;
        return new RagKnowledgeBaseEntity(po.getTenantId(), po.getOwnerUserId(), po.getKnowledgeBaseId(),
                po.getKnowledgeBaseName(), po.getDescription(), readVisibility(po.getVisibility()),
                codec.enumValue(RagKnowledgeBaseStatus.class, po.getStatus(), "知识库状态"),
                po.getRetrievalProfileId(), requiredInt(po.getEmbeddingDimension(), "Embedding维度"),
                po.getCollectionAlias(), requiredLong(po.getCurrentGeneration(), "知识库generation"),
                requiredLong(po.getRevision(), "知识库revision"));
    }

    public RagKnowledgeBasePO toKnowledgeBasePo(RagKnowledgeBaseEntity entity) {
        return RagKnowledgeBasePO.builder().tenantId(entity.tenantId()).ownerUserId(entity.ownerUserId())
                .visibility(writeVisibility(entity.visibility())).knowledgeBaseId(entity.knowledgeBaseId())
                .knowledgeBaseName(entity.name()).description(entity.description())
                .embeddingDimension(entity.embeddingDimension()).collectionAlias(entity.collectionAlias())
                .currentGeneration(entity.currentGeneration()).retrievalProfileId(entity.retrievalProfileId())
                .revision(entity.revision()).status(codec.databaseValue(entity.status())).build();
    }

    public RagDocumentEntity toDocument(RagDocumentPO po) {
        if (po == null) return null;
        return new RagDocumentEntity(po.getTenantId(), po.getOwnerUserId(), readVisibility(po.getVisibility()),
                po.getKnowledgeBaseId(), po.getDocumentId(), po.getFileName(), po.getActiveVersionId(),
                requiredLong(po.getActiveGeneration(), "文档activeGeneration"), po.getTargetGeneration(),
                readDocumentStatus(po.getStatus()), requiredLong(po.getRevision(), "文档revision"));
    }

    public RagDocumentPO toDocumentPo(RagDocumentEntity entity) {
        return RagDocumentPO.builder().tenantId(entity.tenantId()).ownerUserId(entity.ownerUserId())
                .visibility(writeVisibility(entity.visibility())).knowledgeBaseId(entity.knowledgeBaseId())
                .documentId(entity.documentId()).fileName(entity.displayName()).sourceType("upload")
                .documentVersion(1).chunkCount(0)
                .activeVersionId(entity.activeVersionId()).activeGeneration(entity.activeGeneration())
                .targetGeneration(entity.targetGeneration()).status(codec.databaseValue(entity.status()))
                .revision(entity.revision()).build();
    }

    public RagDocumentVersionEntity toDocumentVersion(RagDocumentVersionPO po) {
        if (po == null) return null;
        return new RagDocumentVersionEntity(po.getTenantId(), po.getKnowledgeBaseId(), po.getDocumentId(),
                po.getVersionId(), requiredInt(po.getVersionNumber(), "文档版本号"),
                requiredLong(po.getGeneration(), "文档版本generation"), po.getSourceBucket(),
                po.getSourceObjectKey(), po.getParsedBucket(), po.getParsedObjectKey(),
                po.getFileName(), po.getContentHash(), po.getMimeType(),
                requiredLong(po.getSizeBytes(), "文档字节数"),
                codec.enumValue(RagDocumentVersionStatus.class, po.getStatus(), "文档版本状态"),
                po.getParserVersion(), po.getChunkerVersion(), po.getEmbeddingModelRevision(),
                requiredLong(po.getRowVersion(), "文档版本rowVersion"));
    }

    public RagDocumentVersionPO toDocumentVersionPo(RagDocumentVersionEntity entity) {
        return RagDocumentVersionPO.builder().tenantId(entity.tenantId())
                .knowledgeBaseId(entity.knowledgeBaseId()).documentId(entity.documentId())
                .versionId(entity.versionId()).versionNumber(entity.versionNumber())
                .generation(entity.generation()).sourceBucket(entity.objectBucket())
                .sourceObjectKey(entity.objectKey()).parsedBucket(entity.parsedObjectBucket())
                .parsedObjectKey(entity.parsedObjectKey()).fileName(entity.fileName()).mimeType(entity.mimeType())
                .sizeBytes(entity.sizeBytes()).contentHash(entity.sha256()).parserVersion(entity.parserVersion())
                .chunkerVersion(entity.chunkerVersion()).embeddingModelRevision(entity.embeddingModelRevision())
                .chunkCount(0).status(codec.databaseValue(entity.status())).rowVersion(entity.revision()).build();
    }

    public RagChunkEntity toChunk(RagChunkPO po) {
        if (po == null) return null;
        return new RagChunkEntity(po.getTenantId(), po.getOwnerUserId(), readVisibility(po.getVisibility()),
                po.getKnowledgeBaseId(), po.getDocumentId(), po.getVersionId(),
                requiredInt(po.getDocumentVersion(), "切片文档版本号"),
                requiredLong(po.getGeneration(), "切片generation"), po.getChunkId(),
                requiredInt(po.getChunkIndex(), "切片序号"), po.getParentChunkId(),
                po.getPreviousChunkId(), po.getNextChunkId(), po.getContent(),
                requiredInt(po.getTokenCount(), "切片Token数"), po.getPageFrom(), po.getSectionPath(),
                po.getContentHash(), po.getVectorPointId(), codec.readMetadata(po.getMetadata()));
    }

    public RagChunkPO toChunkPo(RagChunkEntity entity) {
        return RagChunkPO.builder().tenantId(entity.tenantId()).ownerUserId(entity.ownerUserId())
                .visibility(writeVisibility(entity.visibility())).knowledgeBaseId(entity.knowledgeBaseId())
                .documentId(entity.documentId()).versionId(entity.versionId())
                .documentVersion(entity.versionNumber()).generation(entity.generation()).chunkId(entity.chunkId())
                .chunkIndex(entity.chunkIndex()).parentChunkId(entity.parentChunkId())
                .previousChunkId(entity.previousChunkId()).nextChunkId(entity.nextChunkId())
                .sectionPath(entity.headingPath()).pageFrom(entity.pageNumber()).pageTo(entity.pageNumber())
                .content(entity.content()).contentHash(entity.contentHash()).tokenCount(entity.tokenCount())
                .embeddingId(entity.vectorPointId()).vectorPointId(entity.vectorPointId()).status("active")
                .revision(1L).metadata(codec.writeMetadata(entity.metadata())).build();
    }

    public RagIngestJobEntity toIngestJob(RagIngestTaskPO po) {
        if (po == null) return null;
        RagLease lease = readLease(po.getLeaseOwner(), po.getLeaseUntil());
        return new RagIngestJobEntity(po.getTenantId(), po.getKnowledgeBaseId(), po.getDocumentId(),
                po.getVersionId(), po.getTaskId(), po.getTaskKey(),
                codec.enumValue(RagIngestOperation.class, po.getOperation(), "摄取操作"),
                requiredLong(po.getGeneration(), "任务generation"),
                codec.enumValue(RagIngestJobStatus.class, po.getStatus(), "摄取任务状态"),
                codec.readCheckpoint(po.getCheckpoint(), po.getStage()),
                requiredInt(po.getAttemptCount(), "任务尝试次数"),
                requiredInt(po.getMaxAttempts(), "任务最大尝试次数"), toInstant(po.getNextRetryAt()), lease,
                requiredLong(po.getFencingToken(), "任务fencingToken"),
                requiredLong(po.getRowVersion(), "任务rowVersion"), po.getCancelReason(),
                po.getErrorCode(), po.getErrorMessage());
    }

    public RagIngestTaskPO toIngestTaskPo(RagIngestJobEntity entity) {
        return RagIngestTaskPO.builder().taskId(entity.jobId()).taskKey(entity.idempotencyKey())
                .tenantId(entity.tenantId()).knowledgeBaseId(entity.knowledgeBaseId())
                .documentId(entity.documentId()).versionId(entity.versionId()).generation(entity.generation())
                .operation(codec.databaseValue(entity.operation())).stage(codec.databaseValue(entity.checkpoint().stage()))
                .status(codec.databaseValue(entity.status())).attemptCount(entity.attemptCount())
                .maxAttempts(entity.maxAttempts()).nextRetryAt(toLocalDateTime(entity.nextRetryAt()))
                .leaseOwner(entity.lease() == null ? null : entity.lease().owner())
                .leaseUntil(entity.lease() == null ? null : toLocalDateTime(entity.lease().expiresAt()))
                .fencingToken(entity.fencingToken()).rowVersion(entity.revision())
                .checkpoint(codec.writeCheckpoint(entity.checkpoint())).cancelReason(entity.cancelReason())
                .errorCode(entity.errorCode()).errorMessage(entity.errorMessage()).build();
    }

    public RagRetrievalProfileEntity toRetrievalProfile(RagRetrievalProfilePO po) {
        if (po == null) return null;
        boolean denseEnabled = enabled(po.getDenseEnabled(), "Dense开关");
        boolean sparseEnabled = enabled(po.getSparseEnabled(), "Sparse开关");
        RagRetrievalMode mode = readMode(denseEnabled, sparseEnabled);
        return new RagRetrievalProfileEntity(po.getTenantId(), po.getProfileId(), po.getProfileName(), mode,
                codec.enumValue(RagFusionStrategy.class, po.getFusionStrategy(), "融合策略"),
                po.getDenseWeight(), po.getSparseWeight(), requiredInt(po.getDenseTopK(), "Dense TopK"),
                requiredInt(po.getSparseTopK(), "Sparse TopK"), requiredInt(po.getFusionTopK(), "融合 TopK"),
                enabled(po.getRerankEnabled(), "Rerank开关"), requiredInt(po.getRerankTopK(), "Rerank TopK"),
                requiredInt(po.getFinalTopK(), "最终 TopK"), requiredInt(po.getNeighborWindow(), "邻接窗口"),
                requiredInt(po.getMaxContextTokens(), "上下文Token预算"), po.getScoreThreshold(),
                enabled(po.getQueryRewriteEnabled(), "查询改写开关"),
                enabled(po.getDeduplicateEnabled(), "去重开关"), requiredLong(po.getRevision(), "策略revision"));
    }

    public RagRetrievalProfilePO toRetrievalProfilePo(RagRetrievalProfileEntity entity) {
        boolean dense = entity.mode() != RagRetrievalMode.SPARSE;
        boolean sparse = entity.mode() != RagRetrievalMode.DENSE;
        return RagRetrievalProfilePO.builder().tenantId(entity.tenantId()).profileId(entity.profileId())
                .profileName(entity.name()).denseEnabled(dense ? 1 : 0).sparseEnabled(sparse ? 1 : 0)
                .fusionStrategy(codec.databaseValue(entity.fusionStrategy()))
                .denseWeight(entity.denseWeight()).sparseWeight(entity.sparseWeight())
                .denseTopK(entity.denseTopK()).sparseTopK(entity.sparseTopK())
                .fusionTopK(entity.fusionTopK()).rerankEnabled(entity.rerankEnabled() ? 1 : 0)
                .rerankTopK(entity.rerankTopK()).finalTopK(entity.finalTopK())
                .neighborWindow(entity.neighborWindow()).maxContextTokens(entity.maxContextTokens())
                .scoreThreshold(entity.scoreThreshold()).queryRewriteEnabled(entity.queryRewriteEnabled() ? 1 : 0)
                .deduplicateEnabled(entity.deduplicateEnabled() ? 1 : 0).configJson("{}")
                .revision(entity.revision()).status("active").build();
    }

    public RagAgentBindingEntity toAgentBinding(RagAgentBindingPO po) {
        if (po == null) return null;
        return new RagAgentBindingEntity(po.getTenantId(), po.getBindingId(),
                codec.enumValue(RagBindingTargetType.class, po.getTargetType(), "绑定目标类型"),
                po.getTargetId(), po.getKnowledgeBaseId(), po.getProfileId(),
                enabled(po.getRequired(), "绑定required"), requiredInt(po.getMaxTokens(), "绑定Token预算"),
                requiredInt(po.getPriority(), "绑定优先级"), requiredLong(po.getRevision(), "绑定revision"));
    }

    public RagAgentBindingPO toAgentBindingPo(RagAgentBindingEntity entity) {
        return RagAgentBindingPO.builder().tenantId(entity.tenantId()).bindingId(entity.bindingId())
                .targetType(codec.databaseValue(entity.targetType())).targetId(entity.targetId())
                .knowledgeBaseId(entity.knowledgeBaseId()).profileId(entity.retrievalProfileId())
                .priority(entity.priority()).required(entity.required() ? 1 : 0).maxTokens(entity.maxTokens())
                .status("active").revision(entity.revision()).metadata("{}").build();
    }

    private RagDocumentStatus readDocumentStatus(String value) {
        if (value == null) throw new IllegalStateException("文档状态为空");
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "uploading" -> RagDocumentStatus.UPLOADING;
            case "processing", "indexing" -> RagDocumentStatus.PROCESSING;
            case "ready", "active", "indexed" -> RagDocumentStatus.READY;
            case "failed" -> RagDocumentStatus.FAILED;
            case "deleting" -> RagDocumentStatus.DELETING;
            case "deleted" -> RagDocumentStatus.DELETED;
            default -> throw new IllegalStateException("文档状态包含未知值：" + value);
        };
    }

    private RagVisibility readVisibility(String value) {
        if (value == null) throw new IllegalStateException("RAG 可见范围为空");
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "private" -> RagVisibility.PRIVATE;
            case "tenant", "tenant_public" -> RagVisibility.TENANT;
            default -> throw new IllegalStateException("RAG 可见范围包含未知值：" + value);
        };
    }

    private String writeVisibility(RagVisibility visibility) {
        return visibility == RagVisibility.TENANT ? "tenant_public" : "private";
    }

    private RagRetrievalMode readMode(boolean denseEnabled, boolean sparseEnabled) {
        if (denseEnabled && sparseEnabled) return RagRetrievalMode.HYBRID;
        if (denseEnabled) return RagRetrievalMode.DENSE;
        if (sparseEnabled) return RagRetrievalMode.SPARSE;
        throw new IllegalStateException("检索策略不能同时关闭 Dense 和 Sparse");
    }

    private RagLease readLease(String leaseOwner, LocalDateTime leaseUntil) {
        if (leaseOwner == null && leaseUntil == null) return null;
        if (leaseOwner == null || leaseUntil == null) {
            throw new IllegalStateException("RAG 任务租约字段不完整");
        }
        return new RagLease(leaseOwner, toInstant(leaseUntil));
    }

    private boolean enabled(Integer value, String fieldName) {
        if (value == null || value != 0 && value != 1) {
            throw new IllegalStateException(fieldName + "不是0或1");
        }
        return value == 1;
    }

    private int requiredInt(Integer value, String fieldName) {
        if (value == null) throw new IllegalStateException(fieldName + "为空");
        return value;
    }

    private long requiredLong(Long value, String fieldName) {
        if (value == null) throw new IllegalStateException(fieldName + "为空");
        return value;
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
