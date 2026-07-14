package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.ToolDispatchAuthorizationService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;

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
        ToolCallLogEntity existing = ToolCallLogEntity.builder().status("success").outputJson("{\"result\":\"ok\"}").build();
        when(repository.claimToolCallLog(any())).thenReturn(0);
        when(repository.queryToolCallLogByIdempotencyKey(any())).thenReturn(existing);
        ToolDispatchAuthorizationService service = new ToolDispatchAuthorizationService(runService, repository);

        ToolDispatchClaimEntity result = service.claim(tool(), context(), "{}");

        Assert.assertFalse(result.isClaimed());
        Assert.assertSame(existing, result.getCallLog());
        verify(repository).queryToolCallLogByIdempotencyKey(any());
    }

    private ToolCatalogEntity tool() {
        return ToolCatalogEntity.builder().toolId("tool").toolName("工具").toolType("mcp").version("1").build();
    }

    private ToolInvokeContextEntity context() {
        return ToolInvokeContextEntity.builder().tenantId("tenant").userId("user").sessionId("session")
                .runId("run").contextRevision(3L).functionCallId("call").traceId("trace").build();
    }
}
