package cn.bugstack.ai.test.session;

import cn.bugstack.ai.api.dto.session.SessionListResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionRagSettingRequestDTO;
import cn.bugstack.ai.api.dto.session.SessionRagSettingResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.session.service.SessionLifecycleService;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationMetadataService;
import cn.bugstack.ai.domain.rag.service.SessionRagSettingService;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagEligibleBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.trigger.http.SessionController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话历史接口测试。
 */
public class SessionControllerTest {

    @After
    public void cleanup() {
        TenantContextHolder.clear();
    }

    /**
     * 查询会话使用服务端可信身份；无参数；验证请求不能覆盖租户和用户。
     */
    @Test
    public void shouldListSessionsWithTrustedTenantIdentity() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionLifecycleService lifecycleService = mock(SessionLifecycleService.class);
        SessionController controller = new SessionController(sessionDomain, lifecycleService,
                new RagAnswerCitationMetadataService(sessionDomain, new ObjectMapper(), mock(IRagRepository.class)),
                mock(SessionRagSettingService.class));
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("user-1").build());
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 20, 0);
        when(sessionDomain.querySessions("tenant-1", "user-1", null, null, 31)).thenReturn(List.of(
                ChatSessionEntity.builder().sessionId("session-1").agentId("agent-1").agentName("Agent")
                        .title("数据库会话").status("active").lastMessageTime(now).contextRevision(2L).build()));

        Response<SessionListResponseDTO> response = controller.list(null, 30);

        assertEquals("0000", response.getCode());
        assertFalse(response.getData().isHasMore());
        assertEquals("session-1", response.getData().getItems().get(0).getSessionId());
        verify(sessionDomain).querySessions("tenant-1", "user-1", null, null, 31);
    }

    /**
     * 删除会话使用服务端可信身份；无参数；验证调用生命周期服务。
     */
    @Test
    public void shouldDeleteSessionWithTrustedTenantIdentity() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionLifecycleService lifecycleService = mock(SessionLifecycleService.class);
        SessionController controller = new SessionController(sessionDomain, lifecycleService,
                new RagAnswerCitationMetadataService(sessionDomain, new ObjectMapper(), mock(IRagRepository.class)),
                mock(SessionRagSettingService.class));
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("user-1").build());
        when(lifecycleService.delete("tenant-1", "user-1", "session-1")).thenReturn(5L);

        assertEquals(Long.valueOf(5L), Long.valueOf(controller.delete("session-1").getData().getContextRevision()));
        verify(lifecycleService).delete("tenant-1", "user-1", "session-1");
    }

    /**
     * 更新RAG策略透传模式、选择和版本；无参数；验证可信身份及响应摘要。
     */
    @Test
    public void shouldUpdateManualRagSettingWithTrustedIdentity() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionLifecycleService lifecycleService = mock(SessionLifecycleService.class);
        SessionRagSettingService ragSettingService = mock(SessionRagSettingService.class);
        SessionController controller = new SessionController(sessionDomain, lifecycleService,
                new RagAnswerCitationMetadataService(sessionDomain, new ObjectMapper(), mock(IRagRepository.class)),
                ragSettingService);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("user-1").build());
        SessionRagSettingRequestDTO request = new SessionRagSettingRequestDTO();
        request.setMode("MANUAL");
        request.setSelectedBindingIds(List.of("binding-1"));
        request.setExpectedRevision(2L);
        when(ragSettingService.update("tenant-1", "user-1", "session-1", "MANUAL", null,
                List.of("binding-1"), 2L)).thenReturn(new SessionRagSettingEntity(
                "session-1", true, SessionRagMode.MANUAL, 3L, true,
                RagBindingTargetType.AGENT, "agent-1", List.of("binding-1"),
                List.of(new SessionRagEligibleBindingEntity(
                        "binding-1", "kb-1", "知识库一", "profile-1", "默认策略",
                        "ACTIVE", false, 1024, 0, 1L, true))));

        Response<SessionRagSettingResponseDTO> response = controller.updateRagSetting("session-1", request);

        assertEquals("0000", response.getCode());
        assertEquals("MANUAL", response.getData().mode());
        assertEquals(3L, response.getData().revision());
        assertEquals("binding-1", response.getData().eligibleBindings().get(0).bindingId());
        verify(ragSettingService).update("tenant-1", "user-1", "session-1", "MANUAL", null,
                List.of("binding-1"), 2L);
    }
}
