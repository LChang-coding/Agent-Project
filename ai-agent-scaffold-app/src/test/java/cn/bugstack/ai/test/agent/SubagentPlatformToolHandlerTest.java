package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.agent.service.AgentCatalogService;
import cn.bugstack.ai.domain.agent.service.SubagentOrchestrationService;
import cn.bugstack.ai.domain.agent.service.SubagentPlatformToolHandler;
import cn.bugstack.ai.domain.agent.service.AgentToolPermissionService;
import cn.bugstack.ai.domain.agent.service.ToolApprovalService;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SubagentPlatformToolHandlerTest {

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExposeSummaryWithoutLeakingFullContext() {
        SubagentOrchestrationService service = Mockito.mock(SubagentOrchestrationService.class);
        SubagentTaskEntity task = resultTask();
        Mockito.when(service.read("tenant-1", "parent-run-1", List.of("task-1"))).thenReturn(List.of(task));
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new SubagentPlatformToolHandler(registry, Mockito.mock(AgentCatalogService.class), service,
                allowPermissions(), Mockito.mock(ToolApprovalService.class));

        PlatformToolResult result = registry.dispatch(tool("read_subagent_result"),
                Map.of("taskIds", List.of("task-1")), context());

        Map<String, Object> row = ((List<Map<String, Object>>) result.modelResult().get("results")).get(0);
        Assert.assertEquals("bounded summary", row.get("summary"));
        Assert.assertEquals(Boolean.TRUE, row.get("hasFullContext"));
        Assert.assertFalse(row.containsKey("output"));
        Assert.assertFalse(row.containsKey("fullContext"));
    }

    @Test
    public void shouldReadFullContextOnlyWithinTrustedParentScope() {
        SubagentOrchestrationService service = Mockito.mock(SubagentOrchestrationService.class);
        Mockito.when(service.read("tenant-1", "parent-run-1", List.of("task-1")))
                .thenReturn(List.of(resultTask()));
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new SubagentPlatformToolHandler(registry, Mockito.mock(AgentCatalogService.class), service,
                allowPermissions(), Mockito.mock(ToolApprovalService.class));

        PlatformToolResult result = registry.dispatch(tool("read_subagent_full_context"),
                Map.of("taskIds", List.of("task-1")), context());

        Assert.assertTrue(result.success());
        Assert.assertEquals("complete private child context",
                ((Map<?, ?>) ((List<?>) result.modelResult().get("results")).get(0)).get("fullContext"));
        Mockito.verify(service).read("tenant-1", "parent-run-1", List.of("task-1"));
    }

    @Test
    public void shouldCreateSubagentsAfterGatewayAuthorization() {
        SubagentOrchestrationService orchestration = Mockito.mock(SubagentOrchestrationService.class);
        SubagentTaskEntity created = SubagentTaskEntity.builder().taskId("task-1").childAgentId("child-1")
                .status(SubagentTaskStatus.READY).build();
        Mockito.when(orchestration.delegate(Mockito.any(), Mockito.eq("call-1"), Mockito.anyList()))
                .thenReturn(List.of(created));
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new SubagentPlatformToolHandler(registry, Mockito.mock(AgentCatalogService.class), orchestration);
        ToolInvokeContextEntity context = context(); context.setRunId("run-1"); context.setFunctionCallId("call-1");

        PlatformToolResult result = registry.dispatch(tool("create_subagent_instances"), Map.of("tasks",
                List.of(Map.of("agentId", "child-1", "instruction", "research"))), context);

        Assert.assertTrue(result.success());
        Assert.assertEquals("EVENT_DRIVEN", result.modelResult().get("waitMode"));
        Mockito.verify(orchestration).delegate(Mockito.any(), Mockito.eq("call-1"), Mockito.anyList());
    }

    @Test
    public void shouldRejectInputOutsideOriginalSchema() {
        SubagentOrchestrationService orchestration = Mockito.mock(SubagentOrchestrationService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new SubagentPlatformToolHandler(registry, Mockito.mock(AgentCatalogService.class), orchestration);
        ToolInvokeContextEntity context = context(); context.setRunId("run-1"); context.setFunctionCallId("call-1");

        PlatformToolResult result = registry.dispatch(tool("create_subagent_instances"), Map.of("tasks",
                List.of(Map.of("agentId", "child-1", "instruction", "brief")), "untrustedField", true), context);

        Assert.assertFalse(result.success());
        Assert.assertEquals("SUBAGENT_INPUT_INVALID", result.error());
        Mockito.verifyNoInteractions(orchestration);
    }

    @Test
    public void shouldRejectOversizedTaskIdListsAtRuntime() {
        SubagentOrchestrationService service = Mockito.mock(SubagentOrchestrationService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new SubagentPlatformToolHandler(registry, Mockito.mock(AgentCatalogService.class), service,
                allowPermissions(), Mockito.mock(ToolApprovalService.class));

        PlatformToolResult result = registry.dispatch(tool("read_subagent_result"),
                Map.of("taskIds", Collections.nCopies(101, "task")), context());

        Assert.assertFalse(result.success());
        Assert.assertEquals("SUBAGENT_TASK_IDS_INVALID", result.error());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    public void shouldCodeGateCreateAndCancelDuringSummaryOnlyResume() {
        SubagentOrchestrationService orchestration = Mockito.mock(SubagentOrchestrationService.class);
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new SubagentPlatformToolHandler(registry, Mockito.mock(AgentCatalogService.class), orchestration);
        ToolInvokeContextEntity context = context();
        context.setFunctionCallId("call-1");
        context.setOrchestrationSummaryOnly(true);

        PlatformToolResult create = registry.dispatch(tool("create_subagent_instances"), Map.of("tasks",
                List.of(Map.of("agentId", "child-1", "instruction", "research"))), context);
        PlatformToolResult cancel = registry.dispatch(tool("cancel_subagent_instances"),
                Map.of("taskIds", List.of("task-1")), context);

        Assert.assertFalse(create.success());
        Assert.assertEquals("SUBAGENT_SUMMARY_ONLY", create.error());
        Assert.assertFalse(cancel.success());
        Assert.assertEquals("SUBAGENT_SUMMARY_ONLY", cancel.error());
        Mockito.verifyNoInteractions(orchestration);
    }

    private SubagentTaskEntity resultTask() {
        return SubagentTaskEntity.builder().taskId("task-1").childAgentId("child-1")
                .status(SubagentTaskStatus.SUCCEEDED).resultText("legacy result")
                .resultSummary("bounded summary").fullContext("complete private child context")
                .summaryTruncated(true).build();
    }

    private ToolCatalogEntity tool(String function) {
        return ToolCatalogEntity.builder().functionName(function).build();
    }

    private ToolInvokeContextEntity context() {
        return ToolInvokeContextEntity.builder().tenantId("tenant-1").userId("user-1")
                .agentId("parent-1").sessionId("session-1").orchestrationRole("SUPERVISOR")
                .orchestrationRootRunId("parent-run-1").allowedSubAgentIds(List.of("child-1")).build();
    }

    private AgentToolPermissionService allowPermissions() {
        AgentToolPermissionService permissions = Mockito.mock(AgentToolPermissionService.class);
        Mockito.when(permissions.resolve(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(AgentToolPermissionEntity.builder().mode("ALLOW").build());
        return permissions;
    }
}
