package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.service.RagBindingTargetAuthorizationService;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RAG绑定目标存在性、租户范围和运行状态测试。 */
public class RagBindingTargetAuthorizationServiceTest {

    private AgentAvailabilityService agentAvailability;
    private IWorkflowRepository workflowRepository;
    private RagBindingTargetAuthorizationService service;

    @Before
    public void setUp() {
        agentAvailability = mock(AgentAvailabilityService.class);
        workflowRepository = mock(IWorkflowRepository.class);
        service = new RagBindingTargetAuthorizationService(agentAvailability, workflowRepository);
    }

    @Test
    public void shouldAcceptEnabledStaticAgent() {
        when(agentAvailability.isStaticAgent("agent-a")).thenReturn(true);
        when(agentAvailability.isEnabled("tenant-a", "agent-a")).thenReturn(true);

        service.requireAvailable("tenant-a", RagBindingTargetType.AGENT, "agent-a");

        verify(agentAvailability).isEnabled("tenant-a", "agent-a");
    }

    @Test
    public void shouldRejectUnknownOrDisabledAgent() {
        AppException missing = Assert.assertThrows(AppException.class,
                () -> service.requireAvailable("tenant-a", RagBindingTargetType.AGENT, "missing"));
        Assert.assertEquals("RAG_BINDING_TARGET_NOT_FOUND", missing.getCode());

        when(agentAvailability.isStaticAgent("agent-a")).thenReturn(true);
        when(agentAvailability.isEnabled("tenant-a", "agent-a")).thenReturn(false);
        AppException disabled = Assert.assertThrows(AppException.class,
                () -> service.requireAvailable("tenant-a", RagBindingTargetType.AGENT, "agent-a"));
        Assert.assertEquals("RAG_BINDING_TARGET_UNAVAILABLE", disabled.getCode());
    }

    @Test
    public void shouldAcceptOnlyPublishedWorkflowInSameTenant() {
        when(workflowRepository.queryWorkflow("tenant-a", "wf-a")).thenReturn(workflow("tenant-a", "published", 2));

        service.requireAvailable("tenant-a", RagBindingTargetType.WORKFLOW, "wf-a");

        verify(workflowRepository).queryWorkflow("tenant-a", "wf-a");
    }

    @Test
    public void shouldRejectCrossTenantAndDraftWorkflow() {
        when(workflowRepository.queryWorkflow("tenant-a", "wf-cross"))
                .thenReturn(workflow("tenant-b", "published", 1));
        AppException crossTenant = Assert.assertThrows(AppException.class,
                () -> service.requireAvailable("tenant-a", RagBindingTargetType.WORKFLOW, "wf-cross"));
        Assert.assertEquals("RAG_BINDING_TARGET_NOT_FOUND", crossTenant.getCode());

        when(workflowRepository.queryWorkflow("tenant-a", "wf-draft"))
                .thenReturn(workflow("tenant-a", "draft", 0));
        AppException draft = Assert.assertThrows(AppException.class,
                () -> service.requireAvailable("tenant-a", RagBindingTargetType.WORKFLOW, "wf-draft"));
        Assert.assertEquals("RAG_BINDING_TARGET_UNAVAILABLE", draft.getCode());
    }

    private WorkflowEntity workflow(String tenantId, String status, int publishedVersion) {
        return WorkflowEntity.builder().tenantId(tenantId).workflowId("wf-a").status(status)
                .publishedVersion(publishedVersion).build();
    }
}
