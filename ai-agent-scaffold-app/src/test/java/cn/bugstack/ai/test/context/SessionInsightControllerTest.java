package cn.bugstack.ai.test.context;

import cn.bugstack.ai.api.dto.usage.ModelUsageResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.context.service.ContextInsightService;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.trigger.http.SessionInsightController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * 会话洞察接口测试。
 */
public class SessionInsightControllerTest {

    @After
    public void cleanup() {
        TenantContextHolder.clear();
    }

    /**
     * 校验模型用量查询使用可信身份；无参数；验证运行聚合和最新调用同时返回。
     */
    @Test
    public void shouldQuerySessionUsageWithTrustedIdentity() {
        ContextInsightService insightService = Mockito.mock(ContextInsightService.class);
        ModelUsageService usageService = Mockito.mock(ModelUsageService.class);
        SessionDomain sessionDomain = Mockito.mock(SessionDomain.class);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant_1").userId("user_1").build());
        Mockito.when(sessionDomain.assertSessionAccess("tenant_1", "user_1", "session_1", null))
                .thenReturn(ChatSessionEntity.builder().sessionId("session_1").build());
        Mockito.when(usageService.latest("tenant_1", "user_1", "session_1"))
                .thenReturn(ModelUsageEntity.builder().callId("call_1").totalTokens(30).build());
        ModelUsageSummaryEntity summary = ModelUsageSummaryEntity.builder().callCount(1L).successCount(1L)
                .failedCount(0L).promptTokens(20L).candidateTokens(10L).totalTokens(30L)
                .thoughtsTokens(0L).toolUsePromptTokens(0L).build();
        Mockito.when(usageService.summarizeSession("tenant_1", "user_1", "session_1", null)).thenReturn(summary);
        Mockito.when(usageService.summarizeSession("tenant_1", "user_1", "session_1", "run_1")).thenReturn(summary);

        Response<ModelUsageResponseDTO> response = new SessionInsightController(insightService, usageService,
                sessionDomain).sessionUsage("session_1", "run_1");

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("call_1", response.getData().getLatest().getCallId());
        Assert.assertEquals(Long.valueOf(30), response.getData().getRun().getTotalTokens());
        Mockito.verify(sessionDomain).assertSessionAccess("tenant_1", "user_1", "session_1", null);
    }
}
