package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResolver;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRouteIntentRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;
import cn.bugstack.ai.domain.workflow.model.valobj.WorkflowRouteIntentStatus;
import cn.bugstack.ai.domain.workflow.service.WorkflowRoutePlatformToolHandler;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** select_workflow_route 的可信解析与原子意图登记测试。 */
public class WorkflowRoutePlatformToolHandlerTest {

    @Test
    public void shouldNormalizeAndClaimExactTrustedAliasWithoutAdvancingNode() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        when(repository.claim(any())).thenReturn(1);
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult result = handler.handle(tool(), Map.of("routeKey", "  ＰＡＳＳ  ", "reason", "检查通过"),
                context("call-1"));

        Assert.assertTrue(result.success());
        Assert.assertEquals(Boolean.TRUE, result.modelResult().get("registered"));
        Assert.assertEquals("正确", result.modelResult().get("routeKey"));
        ArgumentCaptor<WorkflowRouteIntentEntity> captor = ArgumentCaptor.forClass(WorkflowRouteIntentEntity.class);
        verify(repository).claim(captor.capture());
        WorkflowRouteIntentEntity claimed = captor.getValue();
        Assert.assertEquals("pass", claimed.getNormalizedRouteKey());
        Assert.assertEquals("edge-pass", claimed.getResolvedEdgeId());
        Assert.assertEquals("node-pass", claimed.getResolvedTargetNodeId());
        Assert.assertEquals("node-exec-1", claimed.getNodeExecutionId());
        Assert.assertEquals("a".repeat(64), claimed.getDefinitionHash());
        Assert.assertEquals(Integer.valueOf(3), claimed.getWorkflowVersion());
    }

    @Test
    public void shouldReplayOriginalResultForSameFunctionCallId() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        WorkflowRouteIntentEntity original = intent("call-1", "正确", "正确", "edge-pass", "node-pass");
        when(repository.queryByFunctionCall("tenant-1", "call-1")).thenReturn(original);
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult result = handler.handle(tool(), Map.of("routeKey", "错误", "reason", "迟到重放"),
                context("call-1"));

        Assert.assertTrue(result.success());
        Assert.assertEquals("正确", result.modelResult().get("routeKey"));
        verify(repository, never()).claim(any());
    }

    @Test
    public void shouldReturnExistingIntentForSameNodeAndSameSelection() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        WorkflowRouteIntentEntity original = intent("call-original", "正确", "正确", "edge-pass", "node-pass");
        when(repository.queryByNode("tenant-1", "run-1", "node-exec-1")).thenReturn(original);
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult result = handler.handle(tool(), Map.of("routeKey", "PASS", "reason", "重复选择"),
                context("call-2"));

        Assert.assertTrue(result.success());
        Assert.assertEquals("call-original", result.auditResult().get("functionCallId"));
        verify(repository, never()).claim(any());
    }

    @Test
    public void shouldRejectDifferentSelectionForSameNode() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        when(repository.queryByNode("tenant-1", "run-1", "node-exec-1"))
                .thenReturn(intent("call-original", "正确", "正确", "edge-pass", "node-pass"));
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult result = handler.handle(tool(), Map.of("routeKey", "错误", "reason", "改选"),
                context("call-2"));

        Assert.assertFalse(result.success());
        Assert.assertTrue(result.error().startsWith("WORKFLOW_ROUTE_ALREADY_SELECTED:"));
        verify(repository, never()).claim(any());
    }

    @Test
    public void shouldRejectUnknownKeyAdditionalArgumentsAndUntrustedDefinitionCoordinates() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult unknown = handler.handle(tool(), Map.of("routeKey", "大概正确", "reason", "猜测"),
                context("call-1"));
        PlatformToolResult extra = handler.handle(tool(), Map.of("routeKey", "正确", "reason", "通过",
                "targetNodeId", "attacker-node"), context("call-2"));
        ToolInvokeContextEntity missingHash = context("call-3");
        missingHash.setDefinitionHash(" ");
        PlatformToolResult invalidContext = handler.handle(tool(), Map.of("routeKey", "正确", "reason", "通过"),
                missingHash);

        Assert.assertTrue(unknown.error().startsWith("WORKFLOW_ROUTE_KEY_INVALID:"));
        Assert.assertTrue(extra.error().startsWith("WORKFLOW_ROUTE_INPUT_INVALID:"));
        Assert.assertTrue(invalidContext.error().startsWith("PLATFORM_TOOL_CONTEXT_INVALID:"));
        verify(repository, never()).claim(any());
    }

    @Test
    public void shouldReadConcurrentWinnerAfterAtomicClaimLosesUniqueConstraintRace() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        WorkflowRouteIntentEntity winner = intent("call-1", "正确", "正确", "edge-pass", "node-pass");
        when(repository.claim(any())).thenReturn(0);
        when(repository.queryByFunctionCall("tenant-1", "call-1")).thenReturn(null, winner);
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult result = handler.handle(tool(), Map.of("routeKey", "正确", "reason", "通过"),
                context("call-1"));

        Assert.assertTrue(result.success());
        Assert.assertEquals("call-1", result.auditResult().get("functionCallId"));
    }

    @Test
    public void shouldFailClosedWhenNormalizedDescriptorIsOwnedByDifferentEdges() {
        IWorkflowRouteIntentRepository repository = mock(IWorkflowRouteIntentRepository.class);
        ToolInvokeContextEntity context = context("call-1");
        context.setRouteDescriptors(List.of(
                new PlatformToolResolver.RouteDescriptor("PASS", "edge-pass", "node-pass"),
                new PlatformToolResolver.RouteDescriptor("ｐａｓｓ", "edge-other", "node-other")));
        WorkflowRoutePlatformToolHandler handler = handler(repository);

        PlatformToolResult result = handler.handle(tool(), Map.of("routeKey", "pass", "reason", "通过"), context);

        Assert.assertFalse(result.success());
        Assert.assertTrue(result.error().startsWith("PLATFORM_TOOL_CONTEXT_INVALID:"));
        verify(repository, never()).claim(any());
    }

    private WorkflowRoutePlatformToolHandler handler(IWorkflowRouteIntentRepository repository) {
        return new WorkflowRoutePlatformToolHandler(new PlatformToolRegistry(), repository);
    }

    private ToolCatalogEntity tool() {
        return ToolCatalogEntity.builder().toolType("platform").functionName("select_workflow_route").build();
    }

    private ToolInvokeContextEntity context(String functionCallId) {
        return ToolInvokeContextEntity.builder().tenantId("tenant-1").userId("user-1").runId("run-1")
                .workflowId("workflow-1").workflowKind("INTELLIGENT").routingProtocolVersion("TOOL_V2")
                .workflowVersion("3").definitionHash("a".repeat(64)).nodeExecutionId("node-exec-1")
                .sourceNodeId("review")
                .functionCallId(functionCallId).traceId("trace-1").terminalNode(false)
                .routeDescriptors(List.of(
                        new PlatformToolResolver.RouteDescriptor("正确", "edge-pass", "node-pass"),
                        new PlatformToolResolver.RouteDescriptor("pass", "edge-pass", "node-pass"),
                        new PlatformToolResolver.RouteDescriptor("错误", "edge-fail", "node-fail")))
                .build();
    }

    private WorkflowRouteIntentEntity intent(String functionCallId, String routeKey, String normalizedRouteKey,
                                               String edgeId, String targetNodeId) {
        return WorkflowRouteIntentEntity.builder().tenantId("tenant-1").userId("user-1").runId("run-1")
                .nodeExecutionId("node-exec-1").workflowId("workflow-1").workflowVersion(3)
                .definitionHash("a".repeat(64)).nodeId("node-exec-1").routeKey(routeKey)
                .normalizedRouteKey(normalizedRouteKey).resolvedEdgeId(edgeId).resolvedTargetNodeId(targetNodeId)
                .reason("原始理由").functionCallId(functionCallId).source("MODEL_TOOL")
                .status(WorkflowRouteIntentStatus.PENDING).traceId("trace-1").consumedAt((LocalDateTime) null).build();
    }
}
