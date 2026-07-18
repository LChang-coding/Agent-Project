package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/** 租户管理员只能沿已配置绑定进行检索调试。 */
@Service
public class RagRetrievalDebugService {

    private final IRagRepository repository;
    private final RagKnowledgeBaseAuthorizationService authorization;
    private final RagRetrievalService retrievalService;

    public RagRetrievalDebugService(IRagRepository repository,
                                    RagKnowledgeBaseAuthorizationService authorization,
                                    RagRetrievalService retrievalService) {
        this.repository = repository;
        this.authorization = authorization;
        this.retrievalService = retrievalService;
    }

    public RagRetrievalResult debug(String tenantId, String userId, String roleCode,
                                    RagBindingTargetType targetType, String targetId, String query,
                                    int maxContextTokens, String traceId) {
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        if (targetType == null || targetId == null || targetId.isBlank()) {
            throw new AppException("RAG_DEBUG_TARGET_INVALID", "调试目标不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new AppException("RAG_DEBUG_QUERY_INVALID", "调试问题不能为空");
        }
        String normalizedTarget = targetId.trim();
        if (repository.listBindings(tenantId, targetType, normalizedTarget).isEmpty()) {
            throw new AppException("RAG_DEBUG_TARGET_NOT_BOUND", "当前租户未给该运行目标配置知识库绑定");
        }
        if (maxContextTokens < 1 || maxContextTokens > 32768) {
            throw new AppException("RAG_DEBUG_BUDGET_INVALID", "调试Token预算必须位于1到32768之间");
        }
        return retrievalService.retrieve(new RagRetrievalRequest(tenantId, userId, null, null, targetType,
                normalizedTarget, query, traceId, maxContextTokens));
    }
}
