package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 租户管理员维护检索策略和 Agent/工作流知识库绑定的应用服务。 */
@Service
public class RagRetrievalConfigurationService {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_TARGET_ID_LENGTH = 64;
    private final IRagRepository repository;
    private final RagKnowledgeBaseAuthorizationService authorization;

    public RagRetrievalConfigurationService(IRagRepository repository,
                                            RagKnowledgeBaseAuthorizationService authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    @Transactional(rollbackFor = Exception.class)
    public RagRetrievalProfileEntity createProfile(String tenantId, String userId, String roleCode,
                                                    ProfileValues values) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        String profileId = "profile_" + UUID.randomUUID().toString().replace("-", "");
        RagRetrievalProfileEntity profile = toProfile(tenantId, profileId, 0, values);
        if (repository.insertRetrievalProfile(tenantId, profile) != 1) {
            throw new AppException("RAG_PROFILE_CONFLICT", "检索策略创建冲突");
        }
        return profile;
    }

    @Transactional(rollbackFor = Exception.class)
    public RagRetrievalProfileEntity updateProfile(String tenantId, String userId, String roleCode,
                                                    String profileId, long expectedRevision,
                                                    ProfileValues values) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        RagRetrievalProfileEntity existing = repository.findRetrievalProfile(tenantId, requireId(profileId,
                        "RAG_PROFILE_ID_INVALID", "检索策略ID"))
                .orElseThrow(() -> new AppException("RAG_PROFILE_NOT_FOUND", "检索策略不存在"));
        if (expectedRevision != existing.revision()) {
            throw new AppException("RAG_PROFILE_REVISION_CONFLICT", "检索策略已被其他操作更新");
        }
        RagRetrievalProfileEntity updated = toProfile(tenantId, existing.profileId(), expectedRevision + 1, values);
        if (repository.updateRetrievalProfile(tenantId, updated, expectedRevision) != 1) {
            throw new AppException("RAG_PROFILE_REVISION_CONFLICT", "检索策略已被其他操作更新");
        }
        return updated;
    }

    public List<RagRetrievalProfileEntity> listProfiles(String tenantId, String userId, String roleCode) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        return repository.listRetrievalProfiles(tenantId);
    }

    @Transactional(rollbackFor = Exception.class)
    public RagAgentBindingEntity createBinding(String tenantId, String userId, String roleCode,
                                               BindingValues values) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        if (values == null || values.targetType() == null) {
            throw new AppException("RAG_BINDING_INVALID", "绑定参数不能为空");
        }
        String targetId = requireId(values.targetId(), "RAG_BINDING_TARGET_INVALID", "绑定目标ID");
        if (targetId.length() > MAX_TARGET_ID_LENGTH || values.maxTokens() < 1 || values.maxTokens() > 32768
                || values.priority() < 0 || values.priority() > 10000) {
            throw new AppException("RAG_BINDING_INVALID", "绑定目标、优先级或Token预算非法");
        }
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(tenantId,
                        requireId(values.knowledgeBaseId(), "RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库ID"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        authorization.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        RagRetrievalProfileEntity profile = repository.findRetrievalProfile(tenantId,
                        requireId(values.profileId(), "RAG_PROFILE_NOT_FOUND", "检索策略ID"))
                .orElseThrow(() -> new AppException("RAG_PROFILE_NOT_FOUND", "检索策略不存在"));
        if (values.maxTokens() > profile.maxContextTokens()) {
            throw new AppException("RAG_BINDING_INVALID", "绑定Token预算不能超过检索策略预算");
        }
        RagAgentBindingEntity binding = new RagAgentBindingEntity(tenantId,
                "binding_" + UUID.randomUUID().toString().replace("-", ""), values.targetType(), targetId,
                knowledgeBase.knowledgeBaseId(), profile.profileId(), values.required(), values.maxTokens(),
                values.priority(), 0);
        if (repository.insertBinding(tenantId, binding) != 1) {
            throw new AppException("RAG_BINDING_CONFLICT", "当前目标已绑定该知识库");
        }
        return binding;
    }

    public List<RagAgentBindingEntity> listBindings(String tenantId, String userId, String roleCode) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        return repository.listBindings(tenantId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteBinding(String tenantId, String userId, String roleCode,
                              String bindingId, long expectedRevision) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        RagAgentBindingEntity binding = repository.findBinding(tenantId,
                        requireId(bindingId, "RAG_BINDING_NOT_FOUND", "绑定ID"))
                .orElseThrow(() -> new AppException("RAG_BINDING_NOT_FOUND", "绑定不存在"));
        if (expectedRevision != binding.revision()
                || repository.deleteBinding(tenantId, binding.bindingId(), expectedRevision) != 1) {
            throw new AppException("RAG_BINDING_REVISION_CONFLICT", "绑定已被其他操作更新");
        }
    }

    private RagRetrievalProfileEntity toProfile(String tenantId, String profileId, long revision,
                                                ProfileValues values) {
        if (values == null) throw new AppException("RAG_PROFILE_INVALID", "检索策略参数不能为空");
        String name = values.name() == null ? "" : values.name().trim();
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new AppException("RAG_PROFILE_INVALID", "检索策略名称不能为空且不能超过128字符");
        }
        try {
            return new RagRetrievalProfileEntity(tenantId, profileId, name, values.mode(),
                    values.fusionStrategy(), values.denseWeight(), values.sparseWeight(), values.denseTopK(),
                    values.sparseTopK(), values.fusionTopK(), values.rerankEnabled(), values.rerankTopK(),
                    values.finalTopK(), values.neighborWindow(), values.maxContextTokens(), values.scoreThreshold(),
                    values.queryRewriteEnabled(), values.deduplicateEnabled(), revision);
        } catch (IllegalArgumentException exception) {
            throw new AppException("RAG_PROFILE_INVALID", exception.getMessage());
        }
    }

    private String requireId(String value, String code, String name) {
        if (value == null || value.isBlank()) throw new AppException(code, name + "不能为空");
        return value.trim();
    }

    public record ProfileValues(String name, RagRetrievalMode mode, RagFusionStrategy fusionStrategy,
                                BigDecimal denseWeight, BigDecimal sparseWeight, int denseTopK, int sparseTopK,
                                int fusionTopK, boolean rerankEnabled, int rerankTopK, int finalTopK,
                                int neighborWindow, int maxContextTokens, BigDecimal scoreThreshold,
                                boolean queryRewriteEnabled, boolean deduplicateEnabled) { }

    public record BindingValues(RagBindingTargetType targetType, String targetId, String knowledgeBaseId,
                                String profileId, boolean required, int maxTokens, int priority) { }
}
