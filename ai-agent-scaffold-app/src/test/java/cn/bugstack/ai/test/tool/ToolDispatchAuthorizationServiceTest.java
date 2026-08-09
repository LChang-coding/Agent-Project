package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.ToolDispatchAuthorizationService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具分发授权服务测试。
 */
public class ToolDispatchAuthorizationServiceTest {

    /**
     * 验证先锁定运行再写 started 审计；无参数；无返回值。
     */
    @Test
    public void shouldAuthorizeRunBeforeClaimingStartedLog() {
        RunControlService runService = mock(RunControlService.class);
        IToolRepository repository = mock(IToolRepository.class);
        allowRun(runService, "trace");
        when(repository.claimToolCallLog(any())).thenReturn(1);
        ToolDispatchAuthorizationService service = new ToolDispatchAuthorizationService(runService, repository);

        ToolDispatchClaimEntity result = service.claim(tool(), context(), "{}");

        Assert.assertTrue(result.isClaimed());
        Assert.assertEquals("started", result.getCallLog().getStatus());
        Assert.assertTrue(result.getCallLog().getIdempotencyKey().startsWith("tool_call_sha256_"));
        InOrder order = inOrder(runService, repository);
        order.verify(runService).authorizeToolDispatch("tenant", "user", "run", 3L);
        order.verify(repository).claimToolCallLog(any());
    }

    /**
     * 验证重复调用复用既有审计且不重新取得执行权；无参数；无返回值。
     */
    @Test
    public void shouldReturnExistingLogForDuplicateFunctionCall() {
        RunControlService runService = mock(RunControlService.class);
        IToolRepository repository = mock(IToolRepository.class);
        allowRun(runService, "trace");
        ToolCallLogEntity existing = ToolCallLogEntity.builder().status("success").outputJson("{\"result\":\"ok\"}").build();
        when(repository.claimToolCallLog(any())).thenReturn(0);
        when(repository.queryToolCallLogByIdempotencyKey(any())).thenReturn(existing);
        ToolDispatchAuthorizationService service = new ToolDispatchAuthorizationService(runService, repository);

        ToolDispatchClaimEntity result = service.claim(tool(), context(), "{}");

        Assert.assertFalse(result.isClaimed());
        Assert.assertSame(existing, result.getCallLog());
        verify(repository).queryToolCallLogByIdempotencyKey(any());
    }

    @Test
    public void shouldRejectWorkflowToolOutsideCurrentNodeAllowlist() {
        RunControlService runService = mock(RunControlService.class);
        IToolRepository repository = mock(IToolRepository.class);
        allowRun(runService, "trace");
        ToolDispatchAuthorizationService service = new ToolDispatchAuthorizationService(runService, repository);
        ToolInvokeContextEntity context = context();
        context.setWorkflowKind("INTELLIGENT");
        context.setWorkflowMcpIds(List.of("another_mcp"));

        try {
            service.claim(tool(), context, "{}");
            Assert.fail("节点未配置的 MCP 不应取得执行权");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_TOOL_SCOPE_DENIED", exception.getCode());
        }
    }

    @Test
    public void shouldAllowWorkflowSkillInsideCurrentNodeAllowlist() {
        RunControlService runService = mock(RunControlService.class);
        IToolRepository repository = mock(IToolRepository.class);
        allowRun(runService, "trace");
        when(repository.claimToolCallLog(any())).thenReturn(1);
        ToolDispatchAuthorizationService service = new ToolDispatchAuthorizationService(runService, repository);
        ToolCatalogEntity skill = ToolCatalogEntity.builder().toolId("skill-1").toolName("技能")
                .toolType("skill").version("1").build();
        ToolInvokeContextEntity context = context();
        context.setWorkflowKind("STATIC");
        context.setWorkflowSkillIds(List.of("skill-1"));

        ToolDispatchClaimEntity result = service.claim(skill, context, "{}");

        Assert.assertTrue(result.isClaimed());
    }

    @Test
    public void shouldRejectTraceThatDoesNotMatchLockedRun() {
        RunControlService runService = mock(RunControlService.class);
        IToolRepository repository = mock(IToolRepository.class);
        allowRun(runService, "trace-from-database");
        ToolDispatchAuthorizationService service = new ToolDispatchAuthorizationService(runService, repository);

        try {
            service.claim(tool(), context(), "{}");
            Assert.fail("运行时 Trace ID 与数据库运行不一致时不应执行工具");
        } catch (AppException exception) {
            Assert.assertEquals("TOOL_TRACE_SCOPE_MISMATCH", exception.getCode());
        }
    }

    private void allowRun(RunControlService runService, String traceId) {
        when(runService.authorizeToolDispatch("tenant", "user", "run", 3L))
                .thenReturn(ChatRunEntity.builder().traceId(traceId).build());
    }

    private ToolCatalogEntity tool() {
        return ToolCatalogEntity.builder().toolId("tool").toolName("工具").toolType("mcp").version("1").build();
    }

    private ToolInvokeContextEntity context() {
        return ToolInvokeContextEntity.builder().tenantId("tenant").userId("user").sessionId("session")
                .runId("run").contextRevision(3L).functionCallId("call").traceId("trace").build();
    }
}
