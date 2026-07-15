package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowDomainService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

/** 工作流写权限、活动运行冲突和软删除测试。 */
public class WorkflowLifecycleServiceTest {

    @Test
    public void shouldSoftDeleteOwnedWorkflowAndKeepHistoryOutsideService() {
        IWorkflowRepository workflows = Mockito.mock(IWorkflowRepository.class);
        IChatRunRepository runs = Mockito.mock(IChatRunRepository.class);
        WorkflowDomainService service = service(workflows, runs);
        Mockito.when(workflows.queryWorkflow("tenant-1", "wf-1")).thenReturn(workflow("owner-1"));
        Mockito.when(runs.queryExecutableBySource("tenant-1", "workflow", "wf-1")).thenReturn(List.of());
        Mockito.when(workflows.softDeleteWorkflow("tenant-1", "wf-1", "owner-1")).thenReturn(1);

        service.deleteWorkflow("tenant-1", "owner-1", "member", "wf-1");

        Mockito.verify(workflows).softDeleteWorkflow("tenant-1", "wf-1", "owner-1");
    }

    @Test
    public void shouldRejectNonOwnerAndActiveRunConflict() {
        IWorkflowRepository workflows = Mockito.mock(IWorkflowRepository.class);
        IChatRunRepository runs = Mockito.mock(IChatRunRepository.class);
        WorkflowDomainService service = service(workflows, runs);
        Mockito.when(workflows.queryWorkflow("tenant-1", "wf-1")).thenReturn(workflow("owner-1"));
        try {
            service.deleteWorkflow("tenant-1", "other", "member", "wf-1");
            Assert.fail("非拥有者不能删除工作流");
        } catch (AppException e) {
            Assert.assertEquals("WORKFLOW_WRITE_PERMISSION_DENIED", e.getCode());
        }

        Mockito.when(runs.queryExecutableBySource("tenant-1", "workflow", "wf-1"))
                .thenReturn(List.of(ChatRunEntity.builder().runId("run-1").build()));
        try {
            service.deleteWorkflow("tenant-1", "owner-1", "member", "wf-1");
            Assert.fail("有活动运行时不能删除工作流");
        } catch (AppException e) {
            Assert.assertEquals("WORKFLOW_ACTIVE_RUN_CONFLICT", e.getCode());
        }
        Mockito.verify(workflows, Mockito.never()).softDeleteWorkflow(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private WorkflowDomainService service(IWorkflowRepository workflows, IChatRunRepository runs) {
        WorkflowDomainService service = new WorkflowDomainService();
        ReflectionTestUtils.setField(service, "workflowRepository", workflows);
        ReflectionTestUtils.setField(service, "chatRunRepository", runs);
        return service;
    }

    private WorkflowEntity workflow(String owner) {
        return WorkflowEntity.builder().tenantId("tenant-1").ownerUserId(owner).workflowId("wf-1")
                .workflowName("测试工作流").status("published").build();
    }
}
