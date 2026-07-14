package cn.bugstack.ai.test.share;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.share.adapter.ISessionShareRepository;
import cn.bugstack.ai.domain.share.model.SessionImportEntity;
import cn.bugstack.ai.domain.share.model.SessionShareEntity;
import cn.bugstack.ai.domain.share.model.SessionShareResultEntity;
import cn.bugstack.ai.domain.share.service.SessionShareService;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话分享服务安全测试。
 */
public class SessionShareServiceTest {

    @Test
    public void shouldExportOnlyWhitelistedActiveMessageFields() {
        SessionDomain sessions = mock(SessionDomain.class);
        ISessionShareRepository repository = mock(ISessionShareRepository.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.assetBucket()).thenReturn("assets");
        when(repository.insertShare(any())).thenReturn(1);
        when(sessions.assertSessionAccess("tenant", "owner", "session", null)).thenReturn(session("session", "owner"));
        when(sessions.queryValidMessages("tenant", "owner", "session")).thenReturn(List.of(
                ChatMessageEntity.builder().tenantId("tenant-secret").userId("user-secret").sessionId("session")
                        .messageId("msg-secret").runId("run-secret").traceId("trace-secret")
                        .validityStatus("active").role("user").contentType("text").content("安全内容")
                        .sequenceNo(1).createTime(LocalDateTime.now()).build()));
        SessionShareService service = service(sessions, repository, storage);

        service.create("tenant", "owner", "session", 24, 3);

        ArgumentCaptor<ObjectStorageCommandEntity> captor = ArgumentCaptor.forClass(ObjectStorageCommandEntity.class);
        verify(storage).putObject(captor.capture());
        String json = new String(captor.getValue().getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertTrue(json.contains("安全内容"));
        Assert.assertFalse(json.contains("tenant-secret"));
        Assert.assertFalse(json.contains("user-secret"));
        Assert.assertFalse(json.contains("run-secret"));
        Assert.assertFalse(json.contains("trace-secret"));
        Assert.assertFalse(json.contains("msg-secret"));
    }

    @Test
    public void shouldReturnExistingImportWithoutConsumingAgain() throws Exception {
        SessionDomain sessions = mock(SessionDomain.class);
        ISessionShareRepository repository = mock(ISessionShareRepository.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        SessionShareEntity share = share();
        byte[] bytes = exportBytes();
        share.setContentSha256(sha256(bytes));
        when(repository.queryByTokenHash(any())).thenReturn(share);
        when(repository.lockByShareId("share-1")).thenReturn(share);
        when(repository.queryImport("share-1", sha256("tenant:recipient".getBytes())))
                .thenReturn(SessionImportEntity.builder().newSessionId("imported-session").build());
        when(storage.getObject("assets", "chat-shares/share.json", 8L * 1024 * 1024)).thenReturn(bytes);
        when(sessions.assertSessionAccess("tenant", "recipient", "imported-session", null))
                .thenReturn(session("imported-session", "recipient"));
        when(sessions.queryValidMessages("tenant", "recipient", "imported-session")).thenReturn(List.of());

        SessionShareResultEntity result = service(sessions, repository, storage)
                .importCopy("tenant", "recipient", "abcdefghijklmnopqrstuvwxyz1234567890");

        Assert.assertEquals("imported-session", result.getSession().getSessionId());
        verify(repository, never()).consumeAccess(any());
    }

    private SessionShareService service(SessionDomain sessions, ISessionShareRepository repository,
                                        ObjectStorageService storage) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new SessionShareService(sessions, repository, storage,
                new ObjectMapper().findAndRegisterModules(), provider);
    }

    private ChatSessionEntity session(String sessionId, String userId) {
        return ChatSessionEntity.builder().tenantId("tenant").userId(userId).sessionId(sessionId)
                .agentId("agent").agentName("Agent").appName("app").title("分享会话")
                .status("active").contextRevision(0L).build();
    }

    private SessionShareEntity share() {
        return SessionShareEntity.builder().shareId("share-1").tokenHash("hash").bucket("assets")
                .objectKey("chat-shares/share.json").schemaVersion(SessionShareService.SCHEMA_VERSION)
                .status("active").expiresAt(LocalDateTime.now().plusHours(1)).maxDownloads(10)
                .downloadCount(0).messageCount(1).build();
    }

    private byte[] exportBytes() throws Exception {
        String json = "{\"schemaVersion\":\"chat-session-export/v1\",\"exportedAt\":null,"
                + "\"session\":{\"title\":\"分享\",\"agentId\":\"agent\",\"agentName\":\"Agent\",\"appName\":\"app\"},"
                + "\"messages\":[{\"sequenceNo\":1,\"role\":\"user\",\"contentType\":\"text\",\"content\":\"hello\",\"createdAt\":null}]}";
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
