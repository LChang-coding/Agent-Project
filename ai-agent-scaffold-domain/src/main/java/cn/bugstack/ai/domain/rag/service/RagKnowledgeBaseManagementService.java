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

    private static final int DEFAULT_EMBEDDING_DIMENSION = 768;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    private final IRagRepository repository;
    private final RagKnowledgeBaseAuthorizationService authorizationService;

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

    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new AppException("RAG_KNOWLEDGE_BASE_DESCRIPTION_INVALID", "知识库描述不能超过512个字符");
        }
        return normalized;
    }

    private String collectionAlias(String tenantId, String knowledgeBaseId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(StandardCharsets.UTF_8));
            return "rag_" + HexFormat.of().formatHex(digest, 0, 8) + "_" + knowledgeBaseId;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM缺少SHA-256摘要算法", e);
        }
    }

    private AppException conflict() {
        return new AppException("RAG_KNOWLEDGE_BASE_CONFLICT", "当前租户已存在同名知识库，请更换名称后重试");
    }
}
