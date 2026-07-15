package cn.bugstack.ai.test.session;

import cn.bugstack.ai.api.dto.session.SessionListResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.session.service.SessionLifecycleService;
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
        SessionController controller = new SessionController(sessionDomain, lifecycleService);
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
        SessionController controller = new SessionController(sessionDomain, lifecycleService);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("user-1").build());
        when(lifecycleService.delete("tenant-1", "user-1", "session-1")).thenReturn(5L);

        assertEquals(Long.valueOf(5L), Long.valueOf(controller.delete("session-1").getData().getContextRevision()));
        verify(lifecycleService).delete("tenant-1", "user-1", "session-1");
    }
}
