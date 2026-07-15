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
import java.util.stream.Collectors;

/** 工作流写权限、活动运行冲突和软删除测试。 */
public class WorkflowLifecycleServiceTest {

    @Test
    public void shouldOnlyExposeReadableWorkflowsToMember() {
        IWorkflowRepository workflows = Mockito.mock(IWorkflowRepository.class);
        WorkflowDomainService service = service(workflows, Mockito.mock(IChatRunRepository.class));
        Mockito.when(workflows.queryWorkflowList("tenant-1")).thenReturn(List.of(
                workflow("wf-owned", "member-1", "private"),
                workflow("wf-public", "owner-2", "tenant_public"),
                workflow("wf-private", "owner-2", "private")));

        List<String> visibleIds = service.queryWorkflowList("tenant-1", "member-1", "member").stream()
                .map(WorkflowEntity::getWorkflowId)
                .collect(Collectors.toList());

        Assert.assertEquals(List.of("wf-owned", "wf-public"), visibleIds);
        Assert.assertEquals(3, service.queryWorkflowList("tenant-1", "admin-1", "admin").size());
    }

    @Test
    public void shouldHidePrivateWorkflowFromDetailAndRuntimeBeforeVersionLookup() {
        IWorkflowRepository workflows = Mockito.mock(IWorkflowRepository.class);
        WorkflowDomainService service = service(workflows, Mockito.mock(IChatRunRepository.class));
        Mockito.when(workflows.queryWorkflow("tenant-1", "wf-private"))
                .thenReturn(workflow("wf-private", "owner-1", "private"));

        assertNotFound(() -> service.queryWorkflowDetail("tenant-1", "other", "member", "wf-private"));
        assertNotFound(() -> service.loadRuntime("tenant-1", "other", "member", "wf-private", null, null));

        Mockito.verify(workflows, Mockito.never())
                .queryLatestPublished(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(workflows, Mockito.never())
                .queryVersion(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    public void shouldAllowOwnerAdminAndTenantPublicDetail() {
        IWorkflowRepository workflows = Mockito.mock(IWorkflowRepository.class);
        WorkflowDomainService service = service(workflows, Mockito.mock(IChatRunRepository.class));
        WorkflowEntity privateWorkflow = workflow("wf-private", "owner-1", "private");
        WorkflowEntity publicWorkflow = workflow("wf-public", "owner-2", "tenant_public");
        Mockito.when(workflows.queryWorkflow("tenant-1", "wf-private")).thenReturn(privateWorkflow);
        Mockito.when(workflows.queryWorkflow("tenant-1", "wf-public")).thenReturn(publicWorkflow);

        Assert.assertSame(privateWorkflow,
                service.queryWorkflowDetail("tenant-1", "owner-1", "member", "wf-private").getWorkflow());
        Assert.assertSame(privateWorkflow,
                service.queryWorkflowDetail("tenant-1", "admin-1", "admin", "wf-private").getWorkflow());
        Assert.assertSame(publicWorkflow,
                service.queryWorkflowDetail("tenant-1", "member-2", "member", "wf-public").getWorkflow());
    }

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
        return workflow("wf-1", owner, "private");
    }

    private WorkflowEntity workflow(String workflowId, String owner, String visibility) {
        return WorkflowEntity.builder().tenantId("tenant-1").ownerUserId(owner).workflowId(workflowId)
                .visibility(visibility).workflowName("测试工作流").status("published").build();
    }

    private void assertNotFound(Runnable action) {
        try {
            action.run();
            Assert.fail("私有工作流应对非授权用户隐藏");
        } catch (AppException e) {
            Assert.assertEquals("0002", e.getCode());
            Assert.assertEquals("工作流不存在", e.getInfo());
        }
    }
}
