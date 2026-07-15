package cn.bugstack.ai.test.session;

import cn.bugstack.ai.domain.session.adapter.repository.ISessionRepository;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionDomainTest {

    /**
     * 校验会话和消息保存；无参数；验证 user/assistant 消息按序落入仓储。
     */
    @Test
    public void shouldCreateSessionAndAppendMessagesInOrder() {
        FakeSessionRepository repository = new FakeSessionRepository();
        SessionDomain sessionDomain = new SessionDomain(repository);
        CreateSessionCommandEntity command = createSessionCommand();

        ChatSessionEntity session = sessionDomain.createSession(command);
        ChatMessageEntity userMessage = sessionDomain.appendUserMessage("tenant_1", "user_1", "session_1", "你好", "trace_1");
        ChatMessageEntity assistantMessage = sessionDomain.appendAssistantMessage("tenant_1", "user_1", "session_1", "你好呀", "trace_1");

        Assert.assertEquals("session_1", session.getSessionId());
        Assert.assertEquals("agent", session.getSourceType());
        Assert.assertEquals(Integer.valueOf(1), userMessage.getSequenceNo());
        Assert.assertEquals(Integer.valueOf(2), assistantMessage.getSequenceNo());
        Assert.assertEquals(2, repository.messages.size());
        Assert.assertEquals(2, repository.touchCount);
    }

    @Test
    public void shouldPersistWorkflowRuntimeTargetFacts() {
        FakeSessionRepository repository = new FakeSessionRepository();
        CreateSessionCommandEntity command = createSessionCommand();
        command.setAgentId("wf_1");
        command.setSourceType("workflow");
        command.setWorkflowVersion(3);
        command.setModelCode("gemini-2.5-flash");

        ChatSessionEntity session = new SessionDomain(repository).createSession(command);

        Assert.assertEquals("workflow", session.getSourceType());
        Assert.assertEquals(Integer.valueOf(3), session.getWorkflowVersion());
        Assert.assertEquals("gemini-2.5-flash", session.getModelCode());
    }

    /**
     * 校验租户隔离；无参数；验证错误租户不能访问会话。
     */
    @Test
    public void shouldRejectSessionFromDifferentTenant() {
        FakeSessionRepository repository = new FakeSessionRepository();
        SessionDomain sessionDomain = new SessionDomain(repository);
        sessionDomain.createSession(createSessionCommand());

        try {
            sessionDomain.assertSessionAccess("tenant_2", "user_1", "session_1", "agent_1");
            Assert.fail("不同租户不应该访问到会话");
        } catch (AppException e) {
            Assert.assertEquals(ResponseCode.SESSION_NOT_FOUND.getCode(), e.getCode());
        }
    }

    /**
     * 创建测试会话命令；无参数；返回固定会话命令。
     */
    private CreateSessionCommandEntity createSessionCommand() {
        CreateSessionCommandEntity command = new CreateSessionCommandEntity();
        command.setTenantId("tenant_1");
        command.setUserId("user_1");
        command.setSessionId("session_1");
        command.setAgentId("agent_1");
        command.setAgentName("onlyAgent");
        command.setAppName("testAgent");
        command.setTitle("测试会话");
        return command;
    }

    private static class FakeSessionRepository implements ISessionRepository {

        private final Map<String, ChatSessionEntity> sessions = new HashMap<>();
        private final List<ChatMessageEntity> messages = new ArrayList<>();
        private int touchCount;

        /**
         * 新增会话；参数是会话实体；返回影响行数。
         */
        @Override
        public int insertSession(ChatSessionEntity session) {
            sessions.put(key(session.getTenantId(), session.getUserId(), session.getSessionId()), session);
            return 1;
        }

        /**
         * 查询会话；参数是租户、用户和会话ID；返回会话实体。
         */
        @Override
        public ChatSessionEntity querySession(String tenantId, String userId, String sessionId) {
            return sessions.get(key(tenantId, userId, sessionId));
        }

        /**
         * 锁定会话；参数是租户、用户和会话ID；返回被锁定的会话实体。
         */
        @Override
        public ChatSessionEntity lockSession(String tenantId, String userId, String sessionId) {
            return querySession(tenantId, userId, sessionId);
        }

        @Override
        public List<ChatSessionEntity> querySessions(String tenantId, String userId, LocalDateTime cursorTime,
                                                     String cursorSessionId, int limit) {
            return sessions.values().stream()
                    .filter(session -> key(tenantId, userId, session.getSessionId())
                            .equals(key(session.getTenantId(), session.getUserId(), session.getSessionId())))
                    .limit(limit).toList();
        }

        /**
         * 更新最后消息时间；参数是租户、用户、会话ID和时间；返回影响行数。
         */
        @Override
        public int updateLastMessageTime(String tenantId, String userId, String sessionId, LocalDateTime lastMessageTime) {
            touchCount++;
            return 1;
        }

        /**
         * 查询最大消息序号；参数是租户、用户和会话ID；返回当前最大序号。
         */
        @Override
        public Integer queryMaxSequenceNo(String tenantId, String userId, String sessionId) {
            return messages.stream()
                    .filter(message -> key(tenantId, userId, sessionId).equals(key(message.getTenantId(), message.getUserId(), message.getSessionId())))
                    .map(ChatMessageEntity::getSequenceNo)
                    .max(Integer::compareTo)
                    .orElse(0);
        }

        /**
         * 新增消息；参数是消息实体；返回影响行数。
         */
        @Override
        public int insertMessage(ChatMessageEntity message) {
            messages.add(message);
            return 1;
        }

        @Override
        public long incrementContextRevision(String tenantId, String userId, String sessionId) {
            ChatSessionEntity session = querySession(tenantId, userId, sessionId);
            long revision = session.getContextRevision() == null ? 1L : session.getContextRevision() + 1L;
            session.setContextRevision(revision);
            return revision;
        }

        @Override
        public int invalidateRunMessages(String tenantId, String userId, String sessionId, String runId,
                                         String reason, LocalDateTime invalidatedAt) {
            return 0;
        }

        @Override
        public List<ChatMessageEntity> queryRunMessages(String tenantId, String userId, String sessionId,
                                                        String runId) {
            return messages.stream().filter(message -> runId.equals(message.getRunId())).toList();
        }

        @Override
        public List<ChatMessageEntity> queryValidMessages(String tenantId, String userId, String sessionId) {
            return messages.stream().filter(message -> key(tenantId, userId, sessionId)
                    .equals(key(message.getTenantId(), message.getUserId(), message.getSessionId())))
                    .filter(message -> "active".equals(message.getValidityStatus())).toList();
        }

        @Override
        public List<ChatMessageEntity> queryValidMessagesBefore(String tenantId, String userId, String sessionId,
                                                                Integer beforeSequence, int limit) {
            return queryValidMessages(tenantId, userId, sessionId).stream()
                    .filter(message -> beforeSequence == null || message.getSequenceNo() < beforeSequence)
                    .sorted((left, right) -> right.getSequenceNo().compareTo(left.getSequenceNo()))
                    .limit(limit).toList();
        }

        @Override
        public int softDelete(String tenantId, String userId, String sessionId) {
            return sessions.remove(key(tenantId, userId, sessionId)) == null ? 0 : 1;
        }

        /**
         * 生成仓储键；参数是租户、用户和会话ID；返回字符串键。
         */
        private String key(String tenantId, String userId, String sessionId) {
            return tenantId + ":" + userId + ":" + sessionId;
        }
    }
}
