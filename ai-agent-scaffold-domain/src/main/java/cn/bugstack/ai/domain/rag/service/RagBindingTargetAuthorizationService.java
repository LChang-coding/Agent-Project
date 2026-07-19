package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/** 校验RAG绑定目标确实属于当前租户且处于可运行状态。 */
@Service
public final class RagBindingTargetAuthorizationService {

    private static final String PUBLISHED = "published";

    private final AgentAvailabilityService agentAvailabilityService;
    private final IWorkflowRepository workflowRepository;

    public RagBindingTargetAuthorizationService(AgentAvailabilityService agentAvailabilityService,
                                                IWorkflowRepository workflowRepository) {
        this.agentAvailabilityService = agentAvailabilityService;
        this.workflowRepository = workflowRepository;
    }

    /** 在绑定写库前完成目标存在性、租户范围与可运行状态校验。 */
    public void requireAvailable(String tenantId, RagBindingTargetType targetType, String targetId) {
        if (targetType == RagBindingTargetType.AGENT) {
            requireAgent(tenantId, targetId);
            return;
        }
        if (targetType == RagBindingTargetType.WORKFLOW) {
            requireWorkflow(tenantId, targetId);
            return;
        }
        throw new AppException("RAG_BINDING_TARGET_INVALID", "绑定目标类型不受支持");
    }

    private void requireAgent(String tenantId, String agentId) {
        if (!agentAvailabilityService.isStaticAgent(agentId)) {
            throw notFound();
        }
        if (!agentAvailabilityService.isEnabled(tenantId, agentId)) {
            throw unavailable();
        }
    }

    private void requireWorkflow(String tenantId, String workflowId) {
        WorkflowEntity workflow = workflowRepository.queryWorkflow(tenantId, workflowId);
        if (workflow == null || !tenantId.equals(workflow.getTenantId())) {
            throw notFound();
        }
        if (!PUBLISHED.equalsIgnoreCase(workflow.getStatus())
                || workflow.getPublishedVersion() == null || workflow.getPublishedVersion() < 1) {
            throw unavailable();
        }
    }

    private AppException notFound() {
        return new AppException("RAG_BINDING_TARGET_NOT_FOUND", "绑定目标不存在或不属于当前租户");
    }

    private AppException unavailable() {
        return new AppException("RAG_BINDING_TARGET_UNAVAILABLE", "绑定目标当前不可运行");
    }
}
