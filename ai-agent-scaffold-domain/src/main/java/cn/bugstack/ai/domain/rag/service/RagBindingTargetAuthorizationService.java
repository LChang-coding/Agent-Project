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

    /** 工作流只有已发布状态才允许绑定。 */
    private static final String PUBLISHED = "published";

    /** Agent 可用性包含平台静态注册与租户启用状态。 */
    private final AgentAvailabilityService agentAvailabilityService;
    /** 工作流仓储用于核对租户归属和发布版本。 */
    private final IWorkflowRepository workflowRepository;

    /** 注入 Agent 与工作流两类目标的可信查询入口。 */
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

    /** Agent 必须由平台注册且在当前租户启用。 */
    private void requireAgent(String tenantId, String agentId) {
        if (!agentAvailabilityService.isStaticAgent(agentId)) {
            throw notFound();
        }
        if (!agentAvailabilityService.isEnabled(tenantId, agentId)) {
            throw unavailable();
        }
    }

    /** 工作流必须属于当前租户且至少发布过一个版本。 */
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

    /** 用统一不存在语义隐藏跨租户目标。 */
    private AppException notFound() {
        return new AppException("RAG_BINDING_TARGET_NOT_FOUND", "绑定目标不存在或不属于当前租户");
    }

    /** 目标存在但当前不能运行时返回明确状态。 */
    private AppException unavailable() {
        return new AppException("RAG_BINDING_TARGET_UNAVAILABLE", "绑定目标当前不可运行");
    }
}
