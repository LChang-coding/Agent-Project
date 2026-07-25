package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 租户知识库管理服务。
 */
@Service
public class RagKnowledgeBaseManagementService {

    /** 首期 Embedding 模型和管理字段的固定边界。 */
    private static final int DEFAULT_EMBEDDING_DIMENSION = 768;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    /** 持久化知识库并执行同租户名称检查。 */
    private final IRagRepository repository;
    /** 管理操作统一从可信身份校验租户角色。 */
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    /** 注入 RAG 仓储和授权服务。 */
    public RagKnowledgeBaseManagementService(IRagRepository repository,
                                             RagKnowledgeBaseAuthorizationService authorizationService) {
        this.repository = repository;
        this.authorizationService = authorizationService;
    }

    /** 使用可信租户管理员身份创建知识库。 */
    @Transactional(rollbackFor = Exception.class)
    public RagKnowledgeBaseEntity create(String tenantId, String userId, String roleCode,
                                         String name, String description) {
        authorizationService.requireTenantAdministrator(tenantId, userId, roleCode);
        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        boolean duplicate = repository.listKnowledgeBases(tenantId).stream()
                .map(RagKnowledgeBaseEntity::name)
                .anyMatch(existingName -> normalizedName.equalsIgnoreCase(existingName.trim()));
        if (duplicate) throw conflict();

        String knowledgeBaseId = "kb_" + UUID.randomUUID().toString().replace("-", "");
        RagKnowledgeBaseEntity knowledgeBase = new RagKnowledgeBaseEntity(
                tenantId, userId, knowledgeBaseId, normalizedName, normalizedDescription,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null,
                DEFAULT_EMBEDDING_DIMENSION, collectionAlias(tenantId, knowledgeBaseId), 0L, 0L);
        if (repository.insertKnowledgeBase(tenantId, knowledgeBase) != 1) throw conflict();
        return knowledgeBase;
    }

    /** 查询当前可信租户下的全部知识库。 */
    public List<RagKnowledgeBaseEntity> list(String tenantId, String userId) {
        authorizationService.requireTenantMember(tenantId, userId);
        return repository.listKnowledgeBases(tenantId);
    }

    /** 以revision CAS编辑知识库名称和描述，不改变索引与生命周期字段。 */
    @Transactional(rollbackFor = Exception.class)
    public RagKnowledgeBaseEntity update(String tenantId, String userId, String roleCode,
                                         String knowledgeBaseId, long expectedRevision,
                                         String name, String description) {
        authorizationService.requireTenantAdministrator(tenantId, userId, roleCode);
        RagKnowledgeBaseEntity existing = repository.findKnowledgeBase(tenantId, requireId(knowledgeBaseId))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        authorizationService.requireManageable(tenantId, userId, roleCode, existing);
        if (existing.status() == RagKnowledgeBaseStatus.DELETING
                || existing.status() == RagKnowledgeBaseStatus.DELETED) {
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "删除中的知识库不能编辑");
        }
        if (expectedRevision != existing.revision()) {
            throw revisionConflict();
        }
        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        boolean duplicate = repository.listKnowledgeBases(tenantId).stream()
                .filter(item -> !existing.knowledgeBaseId().equals(item.knowledgeBaseId()))
                .map(RagKnowledgeBaseEntity::name)
                .anyMatch(value -> normalizedName.equalsIgnoreCase(value.trim()));
        if (duplicate) throw conflict();
        RagKnowledgeBaseEntity updated = new RagKnowledgeBaseEntity(existing.tenantId(), existing.ownerUserId(),
                existing.knowledgeBaseId(), normalizedName, normalizedDescription, existing.visibility(),
                existing.status(), existing.retrievalProfileId(), existing.embeddingDimension(),
                existing.collectionAlias(), existing.currentGeneration(), existing.revision() + 1);
        if (repository.updateKnowledgeBase(tenantId, updated, expectedRevision) != 1) {
            throw revisionConflict();
        }
        return updated;
    }

    /** 去除首尾空白并限制知识库展示名长度。 */
    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new AppException("RAG_KNOWLEDGE_BASE_NAME_INVALID", "知识库名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new AppException("RAG_KNOWLEDGE_BASE_NAME_INVALID", "知识库名称不能超过128个字符");
        }
        return normalized;
    }

    /** 对非法 ID 返回不存在，避免暴露标识校验细节。 */
    private String requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在");
        }
        return value.trim();
    }

    /** 空描述落为 null，其余描述只做首尾清理和长度限制。 */
    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new AppException("RAG_KNOWLEDGE_BASE_DESCRIPTION_INVALID", "知识库描述不能超过512个字符");
        }
        return normalized;
    }

    /** 用租户摘要隔离 Qdrant 别名，同时保留知识库可追踪性。 */
    private String collectionAlias(String tenantId, String knowledgeBaseId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(StandardCharsets.UTF_8));
            return "rag_" + HexFormat.of().formatHex(digest, 0, 8) + "_" + knowledgeBaseId;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM缺少SHA-256摘要算法", e);
        }
    }

    /** 统一同名或并发插入冲突响应。 */
    private AppException conflict() {
        return new AppException("RAG_KNOWLEDGE_BASE_CONFLICT", "当前租户已存在同名知识库，请更换名称后重试");
    }

    /** CAS 失败要求客户端刷新后重试。 */
    private AppException revisionConflict() {
        return new AppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", "知识库已被其他操作更新，请刷新后重试");
    }
}
