package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.domain.rag.service.RagRetrievalDebugService;
import cn.bugstack.ai.domain.rag.service.RagRetrievalService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理员 RAG 调试的权限、绑定范围和可信请求测试。 */
public class RagRetrievalDebugServiceTest {

    private IRagRepository repository;
    private RagRetrievalService retrievalService;
    private RagRetrievalDebugService service;

    @Before
    public void setUp() {
        repository = Mockito.mock(IRagRepository.class);
        retrievalService = Mockito.mock(RagRetrievalService.class);
        service = new RagRetrievalDebugService(repository, new RagKnowledgeBaseAuthorizationService(), retrievalService);
    }

    @Test
    public void shouldRequireTenantAdministratorBeforeReadingBindings() {
        AppException error = Assert.assertThrows(AppException.class, () -> service.debug(
                "tenant-a", "member-a", "member", RagBindingTargetType.AGENT,
                "agent-a", "如何退货？", 1024, "trace-a"));

        Assert.assertEquals("RAG_ADMIN_REQUIRED", error.getCode());
        verify(repository, never()).listBindings(any(), any(), any());
        verify(retrievalService, never()).retrieve(any());
    }

    @Test
    public void shouldRejectUnboundTargetWithoutCallingRetrieval() {
        when(repository.listBindings("tenant-a", RagBindingTargetType.WORKFLOW, "workflow-a"))
                .thenReturn(List.of());

        AppException error = Assert.assertThrows(AppException.class, () -> service.debug(
                "tenant-a", "admin-a", "admin", RagBindingTargetType.WORKFLOW,
                "workflow-a", "如何退货？", 1024, "trace-a"));

        Assert.assertEquals("RAG_DEBUG_TARGET_NOT_BOUND", error.getCode());
        verify(retrievalService, never()).retrieve(any());
    }

    @Test
    public void shouldBuildRetrievalRequestOnlyFromTrustedIdentityAndBoundTarget() {
        when(repository.listBindings("tenant-a", RagBindingTargetType.AGENT, "agent-a"))
                .thenReturn(List.of(binding()));
        RagRetrievalResult expected = RagRetrievalResult.empty("retrieval-a", 9);
        when(retrievalService.retrieve(any())).thenReturn(expected);

        RagRetrievalResult actual = service.debug("tenant-a", "owner-a", "owner",
                RagBindingTargetType.AGENT, " agent-a ", "如何退货？", 2048, "trace-a");

        Assert.assertSame(expected, actual);
        ArgumentCaptor<RagRetrievalRequest> captor = ArgumentCaptor.forClass(RagRetrievalRequest.class);
        verify(retrievalService).retrieve(captor.capture());
        RagRetrievalRequest request = captor.getValue();
        Assert.assertEquals("tenant-a", request.tenantId());
        Assert.assertEquals("owner-a", request.userId());
        Assert.assertEquals("agent-a", request.targetId());
        Assert.assertNull(request.sessionId());
        Assert.assertNull(request.runId());
        Assert.assertEquals(2048, request.maxContextTokens());
    }

    @Test
    public void shouldReturnStableErrorForBlankQueryBeforeRetrieval() {
        AppException error = Assert.assertThrows(AppException.class, () -> service.debug(
                "tenant-a", "admin-a", "admin", RagBindingTargetType.AGENT,
                "agent-a", "  ", 1024, "trace-a"));

        Assert.assertEquals("RAG_DEBUG_QUERY_INVALID", error.getCode());
        verify(retrievalService, never()).retrieve(any());
    }

    private RagAgentBindingEntity binding() {
        return new RagAgentBindingEntity("tenant-a", "binding-a", RagBindingTargetType.AGENT,
                "agent-a", "kb-a", "profile-a", false, 1024, 0, 1);
    }
}
