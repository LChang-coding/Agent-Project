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

    /** 稳定枚举、JSON、checkpoint 与元数据编码器。 */
    private final RagPersistenceCodec codec;

    /** 知识库 PO/领域对象双向映射。 */
    public RagKnowledgeBaseEntity toKnowledgeBase(RagKnowledgeBasePO po) {
        if (po == null) return null;
        return new RagKnowledgeBaseEntity(po.getTenantId(), po.getOwnerUserId(), po.getKnowledgeBaseId(),
                po.getKnowledgeBaseName(), po.getDescription(), readVisibility(po.getVisibility()),
                codec.enumValue(RagKnowledgeBaseStatus.class, po.getStatus(), "知识库状态"),
                po.getRetrievalProfileId(), requiredInt(po.getEmbeddingDimension(), "Embedding维度"),
                po.getCollectionAlias(), requiredLong(po.getCurrentGeneration(), "知识库generation"),
                requiredLong(po.getRevision(), "知识库revision"));
    }

    /** 将知识库领域状态写成数据库约定的枚举值和可见范围。 */
    public RagKnowledgeBasePO toKnowledgeBasePo(RagKnowledgeBaseEntity entity) {
        return RagKnowledgeBasePO.builder().tenantId(entity.tenantId()).ownerUserId(entity.ownerUserId())
                .visibility(writeVisibility(entity.visibility())).knowledgeBaseId(entity.knowledgeBaseId())
                .knowledgeBaseName(entity.name()).description(entity.description())
                .embeddingDimension(entity.embeddingDimension()).collectionAlias(entity.collectionAlias())
                .currentGeneration(entity.currentGeneration()).retrievalProfileId(entity.retrievalProfileId())
                .revision(entity.revision()).status(codec.databaseValue(entity.status())).build();
    }

    /** 文档聚合根 PO/领域对象映射。 */
    public RagDocumentEntity toDocument(RagDocumentPO po) {
        if (po == null) return null;
        return new RagDocumentEntity(po.getTenantId(), po.getOwnerUserId(), readVisibility(po.getVisibility()),
                po.getKnowledgeBaseId(), po.getDocumentId(), po.getFileName(), po.getActiveVersionId(),
                requiredLong(po.getActiveGeneration(), "文档activeGeneration"), po.getTargetGeneration(),
                readDocumentStatus(po.getStatus()), requiredLong(po.getRevision(), "文档revision"),
                optionalInt(po.getPageCount()), optionalInt(po.getChunkCount()));
    }

    /** 将逻辑文档写成数据库记录，并保留活动版本与目标代次。 */
    public RagDocumentPO toDocumentPo(RagDocumentEntity entity) {
        return RagDocumentPO.builder().tenantId(entity.tenantId()).ownerUserId(entity.ownerUserId())
                .visibility(writeVisibility(entity.visibility())).knowledgeBaseId(entity.knowledgeBaseId())
                .documentId(entity.documentId()).fileName(entity.displayName()).sourceType("upload")
                .documentVersion(1).pageCount(entity.pageCount()).chunkCount(entity.chunkCount())
                .activeVersionId(entity.activeVersionId()).activeGeneration(entity.activeGeneration())
                .targetGeneration(entity.targetGeneration()).status(codec.databaseValue(entity.status()))
                .revision(entity.revision()).build();
    }

    /** 不可变文档版本 PO/领域对象映射。 */
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
                requiredLong(po.getRowVersion(), "文档版本rowVersion"), optionalInt(po.getPageCount()),
                optionalLong(po.getCharacterCount()), optionalInt(po.getChunkCount()),
                codec.readMetadata(po.getMetadata()));
    }

    /** 将不可变版本及解析产物、质量元数据写成数据库记录。 */
    public RagDocumentVersionPO toDocumentVersionPo(RagDocumentVersionEntity entity) {
        return RagDocumentVersionPO.builder().tenantId(entity.tenantId())
                .knowledgeBaseId(entity.knowledgeBaseId()).documentId(entity.documentId())
                .versionId(entity.versionId()).versionNumber(entity.versionNumber())
                .generation(entity.generation()).sourceBucket(entity.objectBucket())
                .sourceObjectKey(entity.objectKey()).parsedBucket(entity.parsedObjectBucket())
                .parsedObjectKey(entity.parsedObjectKey()).fileName(entity.fileName()).mimeType(entity.mimeType())
                .sizeBytes(entity.sizeBytes()).contentHash(entity.sha256()).parserVersion(entity.parserVersion())
                .chunkerVersion(entity.chunkerVersion()).embeddingModelRevision(entity.embeddingModelRevision())
                .pageCount(entity.pageCount()).characterCount(entity.characterCount()).chunkCount(entity.chunkCount())
                .metadata(codec.writeMetadata(entity.metadata()))
                .status(codec.databaseValue(entity.status())).rowVersion(entity.revision()).build();
    }

    /** 分块结构、位置和向量身份映射。 */
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

    /** 将分块正文、结构位置和向量点身份写成活动分块记录。 */
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

    /** 恢复摄取任务状态机、租约和 checkpoint。 */
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
                po.getErrorCode(), po.getErrorMessage(), po.getTraceId());
    }

    /** 将任务状态机、检查点、租约和错误信息完整写回任务账本。 */
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
                .errorCode(entity.errorCode()).errorMessage(entity.errorMessage()).traceId(entity.traceId()).build();
    }

    /** 恢复检索策略并严格校验布尔标记。 */
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

    /** 将领域检索模式拆成数据库 Dense/Sparse 开关，并写入完整检索参数。 */
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

    /** 恢复 Agent/Workflow 到知识库的绑定。 */
    public RagAgentBindingEntity toAgentBinding(RagAgentBindingPO po) {
        if (po == null) return null;
        return new RagAgentBindingEntity(po.getTenantId(), po.getBindingId(),
                codec.enumValue(RagBindingTargetType.class, po.getTargetType(), "绑定目标类型"),
                po.getTargetId(), po.getKnowledgeBaseId(), po.getProfileId(),
                enabled(po.getRequired(), "绑定required"), requiredInt(po.getMaxTokens(), "绑定Token预算"),
                requiredInt(po.getPriority(), "绑定优先级"), requiredLong(po.getRevision(), "绑定revision"));
    }

    /** 将目标与知识库的绑定写成活动记录，并保留优先级和 Token 上限。 */
    public RagAgentBindingPO toAgentBindingPo(RagAgentBindingEntity entity) {
        return RagAgentBindingPO.builder().tenantId(entity.tenantId()).bindingId(entity.bindingId())
                .targetType(codec.databaseValue(entity.targetType())).targetId(entity.targetId())
                .knowledgeBaseId(entity.knowledgeBaseId()).profileId(entity.retrievalProfileId())
                .priority(entity.priority()).required(entity.required() ? 1 : 0).maxTokens(entity.maxTokens())
                .status("active").revision(entity.revision()).metadata("{}").build();
    }

    /** 兼容历史数据库状态名称，并拒绝无法解释的文档状态。 */
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

    /** 将数据库 private/tenant_public 及历史 tenant 值恢复为领域可见范围。 */
    private RagVisibility readVisibility(String value) {
        if (value == null) throw new IllegalStateException("RAG 可见范围为空");
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "private" -> RagVisibility.PRIVATE;
            case "tenant", "tenant_public" -> RagVisibility.TENANT;
            default -> throw new IllegalStateException("RAG 可见范围包含未知值：" + value);
        };
    }

    /** 使用数据库现行 tenant_public/private 约定保存领域可见范围。 */
    private String writeVisibility(RagVisibility visibility) {
        return visibility == RagVisibility.TENANT ? "tenant_public" : "private";
    }

    /** 根据两个持久化开关恢复唯一检索模式，禁止两路召回同时关闭。 */
    private RagRetrievalMode readMode(boolean denseEnabled, boolean sparseEnabled) {
        if (denseEnabled && sparseEnabled) return RagRetrievalMode.HYBRID;
        if (denseEnabled) return RagRetrievalMode.DENSE;
        if (sparseEnabled) return RagRetrievalMode.SPARSE;
        throw new IllegalStateException("检索策略不能同时关闭 Dense 和 Sparse");
    }

    /** 只有持有者和到期时间同时存在时才恢复任务租约，半条租约视为脏数据。 */
    private RagLease readLease(String leaseOwner, LocalDateTime leaseUntil) {
        if (leaseOwner == null && leaseUntil == null) return null;
        if (leaseOwner == null || leaseUntil == null) {
            throw new IllegalStateException("RAG 任务租约字段不完整");
        }
        return new RagLease(leaseOwner, toInstant(leaseUntil));
    }

    /** 数据库布尔值只接受 0/1，拒绝脏值静默转真。 */
    private boolean enabled(Integer value, String fieldName) {
        if (value == null || value != 0 && value != 1) {
            throw new IllegalStateException(fieldName + "不是0或1");
        }
        return value == 1;
    }

    /** 读取不能为空的整数列，缺失时立即暴露持久化数据问题。 */
    private int requiredInt(Integer value, String fieldName) {
        if (value == null) throw new IllegalStateException(fieldName + "为空");
        return value;
    }

    /** 读取不能为空的长整数列，缺失时立即暴露持久化数据问题。 */
    private long requiredLong(Long value, String fieldName) {
        if (value == null) throw new IllegalStateException(fieldName + "为空");
        return value;
    }

    /** 将兼容期允许为空的统计整数恢复为领域默认值 0。 */
    private int optionalInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** 将兼容期允许为空的统计长整数恢复为领域默认值 0。 */
    private long optionalLong(Long value) {
        return value == null ? 0L : value;
    }

    /** 按数据库统一使用的 UTC 时区将本地时间恢复为时间点。 */
    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** 按 UTC 将领域时间点转换为数据库时间。 */
    private LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
