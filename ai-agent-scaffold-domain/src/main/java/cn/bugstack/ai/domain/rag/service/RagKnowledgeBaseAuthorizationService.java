package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * 知识库管理员权限服务。
 * <p>身份必须来自可信上下文；浏览器字段不能决定租户或角色。</p>
 */
@Service
public final class RagKnowledgeBaseAuthorizationService {

    /** 校验当前身份是可信租户成员。 */
    public void requireTenantMember(String tenantId, String userId) {
        if (isBlank(tenantId) || isBlank(userId)) {
            throw new AppException("RAG_AUTH_CONTEXT_MISSING", "缺少可信租户或用户身份");
        }
    }

    /** 校验当前身份是租户 owner 或 admin。 */
    public void requireTenantAdministrator(String tenantId, String userId, String roleCode) {
        requireTenantMember(tenantId, userId);
        if (!"owner".equalsIgnoreCase(roleCode) && !"admin".equalsIgnoreCase(roleCode)) {
            throw new AppException("RAG_ADMIN_REQUIRED", "仅租户管理员可以维护知识库");
        }
    }

    /** 校验管理员可以管理指定租户知识库。 */
    public void requireManageable(String tenantId, String userId, String roleCode,
                                  RagKnowledgeBaseEntity knowledgeBase) {
        requireTenantAdministrator(tenantId, userId, roleCode);
        if (knowledgeBase == null || !tenantId.equals(knowledgeBase.tenantId())) {
            throw new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问");
        }
    }

    /** 校验资源实体确实属于当前可信租户。 */
    public void requireTenantScope(String tenantId, String resourceTenantId) {
        if (isBlank(tenantId) || isBlank(resourceTenantId) || !tenantId.equals(resourceTenantId)) {
            throw new AppException("RAG_TENANT_SCOPE_MISMATCH", "RAG 资源不属于当前租户");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
