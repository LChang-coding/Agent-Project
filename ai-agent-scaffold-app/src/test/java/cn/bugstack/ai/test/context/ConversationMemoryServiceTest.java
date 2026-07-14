package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.context.adapter.port.ContextCompressionPort;
import cn.bugstack.ai.domain.context.adapter.port.ContextCompactionPublisher;
import cn.bugstack.ai.domain.context.adapter.port.ContextContributor;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCacheRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextHistoryRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IConversationMemoryRepository;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextAssemblyResult;
import cn.bugstack.ai.domain.context.model.ContextCompactionCommand;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskStatus;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.context.model.ContextTaskCreateCommand;
import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话长期记忆服务测试。
 */
public class ConversationMemoryServiceTest {

    /**
     * 校验工作流会话通过运行时 appName 解析压缩模型；无参数；验证工作流 ID 不会被误当为运行时 Agent ID。
     */
    @Test
    public void shouldResolveWorkflowCompressionModelFromSessionAppName() throws Exception {
        ChatModel expectedModel = Mockito.mock(ChatModel.class);
        FakeArmoryFactory armoryFactory = new FakeArmoryFactory();
        armoryFactory.register("wf_node_runtime", AiAgentRegisterVO.builder().chatModel(expectedModel).build());
        ConversationMemoryService service = newService(new FixedSessionDomain(ChatSessionEntity.builder()
                .tenantId("tenant_1")
                .userId("user_1")
                .sessionId("session_1")
                .agentId("wf_123")
                .appName("wf_node_runtime")
                .build()), armoryFactory);

        java.lang.reflect.Method method = ConversationMemoryService.class
                .getDeclaredMethod("resolveChatModel", ContextCompactionTaskEntity.class);
        method.setAccessible(true);
        ChatModel actual;
        try {
            actual = (ChatModel) method.invoke(service, ContextCompactionTaskEntity.builder()
                    .tenantId("tenant_1")
                    .userId("user_1")
                    .sessionId("session_1")
                    .build());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assert.fail(e.getCause().getMessage());
            return;
        }

        Assert.assertSame(expectedModel, actual);
    }

    /**
     * 校验已持久化消息进入会话短期窗口；无参数；验证 Redis 窗口按会话保存最新消息。
     */
    @Test
    public void shouldAppendSavedMessageToRecentConversationWindow() throws Exception {
        Fixture fixture = new Fixture();

        java.lang.reflect.Method method = Arrays.stream(ConversationMemoryService.class.getMethods())
                .filter(candidate -> "onMessageSaved".equals(candidate.getName())
                        && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == ChatMessageEntity.class)
                .findFirst()
                .orElse(null);

        Assert.assertNotNull("会话记忆服务必须接收所有已持久化消息", method);
        method.invoke(fixture.service, message(1, "user", "请记住我的名字是小李", 8));
        Assert.assertEquals(1, fixture.cache.recentMessages.size());
        Assert.assertEquals("请记住我的名字是小李", fixture.cache.recentMessages.get(0).getContent());
    }

    /**
     * 校验上下文切面；无参数；验证当前输入之后的消息不会被注入。
     */
    @Test
    public void shouldAssembleOnlyMessagesVisibleThroughCutoff() {
        Fixture fixture = new Fixture();
        fixture.memory.active = ConversationMemorySnapshotEntity.builder()
                .tenantId("tenant_1")
                .userId("user_1")
                .sessionId("session_1")
                .memoryVersion(1)
                .coveredToSequence(2)
                .content("{\"conversationSummary\":\"旧摘要\",\"confirmedDecisions\":[],\"constraints\":[],\"openItems\":[],\"keyEntities\":[]}")
                .estimatedTokenCount(10)
                .status("active")
                .build();
        fixture.history.messages.add(message(3, "assistant", "历史可见消息", 4));
        fixture.history.messages.add(message(4, "user", "当前输入不应出现", 6));

        ContextAssemblyResult result = fixture.service.assemble(ContextAssembleRequest.builder()
                .tenantId("tenant_1")
                .userId("user_1")
                .sessionId("session_1")
                .visibleThroughSequence(3)
                .build());

        Assert.assertTrue(result.getInstruction().contains("历史可见消息"));
        Assert.assertFalse(result.getInstruction().contains("当前输入不应出现"));
        Assert.assertEquals(Integer.valueOf(1), result.getMemoryVersion());
    }

    /**
     * 校验压缩任务创建；无参数；验证达到阈值后发布 Kafka 命令。
     */
    @Test
    public void shouldCreateAndPublishCompactionTaskWhenThresholdReached() {
        Fixture fixture = new Fixture();
        fixture.properties.setCompactionMinUncoveredTokens(10);
        fixture.properties.setCompactionRetainRecentTokens(5);
        fixture.history.messages.add(message(1, "user", "一", 5));
        fixture.history.messages.add(message(2, "assistant", "二", 5));
        fixture.history.messages.add(message(3, "user", "三", 5));
        fixture.history.messages.add(message(4, "assistant", "四", 5));

        fixture.service.onAssistantMessageSaved(message(4, "assistant", "四", 5));

        Assert.assertEquals(1, fixture.publisher.commands.size());
        ContextCompactionCommand command = fixture.publisher.commands.get(0);
        Assert.assertEquals(Integer.valueOf(1), command.fromSequence());
        Assert.assertEquals(Integer.valueOf(3), command.toSequence());
        Assert.assertEquals(Integer.valueOf(0), command.expectedMemoryVersion());
    }

    private static ChatMessageEntity message(int sequence, String role, String content, int tokens) {
        return ChatMessageEntity.builder()
                .tenantId("tenant_1")
                .userId("user_1")
                .sessionId("session_1")
                .messageId("msg_" + sequence)
                .role(role)
                .contentType("text")
                .content(content)
                .estimatedTokenCount(tokens)
                .sequenceNo(sequence)
                .traceId("trace_1")
                .build();
    }

    private static ConversationMemoryService newService(SessionDomain sessionDomain, DefaultArmoryFactory armoryFactory) {
        return new ConversationMemoryService(new FakeMemoryRepository(), new FakeTaskRepository(), new FakeHistoryRepository(),
                new FakeCacheRepository(), new FakePublisher(), new FakeCompressionPort(), List.<ContextContributor>of(), properties(),
                new ObjectMapper(), sessionDomain, new StaticObjectProvider(armoryFactory), null);
    }

    private static ContextPolicyProperties properties() {
        ContextPolicyProperties value = new ContextPolicyProperties();
        value.setEnabled(true);
        value.setModelWindowTokens(32000);
        value.setReserveOutputTokens(1000);
        value.setSafetyMarginTokens(1000);
        value.setRecentConversationTokens(100);
        value.setLongTermMemoryTokens(100);
        value.setUpstreamTokens(100);
        value.setRagTokens(0);
        value.setPolicyVersion("v1");
        value.setCacheTtlSeconds(10);
        return value;
    }

    private static class Fixture {
        private final FakeMemoryRepository memory = new FakeMemoryRepository();
        private final FakeTaskRepository task = new FakeTaskRepository();
        private final FakeHistoryRepository history = new FakeHistoryRepository();
        private final FakeCacheRepository cache = new FakeCacheRepository();
        private final FakePublisher publisher = new FakePublisher();
        private final ContextPolicyProperties properties = ConversationMemoryServiceTest.properties();
        private final ConversationMemoryService service = new ConversationMemoryService(memory, task, history, cache,
                publisher, new FakeCompressionPort(), List.<ContextContributor>of(), properties,
                new ObjectMapper(), new FixedSessionDomain(ChatSessionEntity.builder()
                        .tenantId("tenant_1").userId("user_1").sessionId("session_1").contextRevision(0L).build()),
                (ObjectProvider<DefaultArmoryFactory>) null, null);

    }

    private static class FixedSessionDomain extends SessionDomain {
        private final ChatSessionEntity session;

        private FixedSessionDomain(ChatSessionEntity session) {
            super(null);
            this.session = session;
        }

        @Override
        public ChatSessionEntity assertSessionAccess(String tenantId, String userId, String sessionId, String agentId) {
            return session;
        }

        @Override
        public ChatSessionEntity lockSessionAccess(String tenantId, String userId, String sessionId, String agentId) {
            return session;
        }
    }

    private static class FakeArmoryFactory extends DefaultArmoryFactory {
        private final Map<String, AiAgentRegisterVO> agents = new HashMap<>();

        private void register(String agentId, AiAgentRegisterVO agent) {
            agents.put(agentId, agent);
        }

        @Override
        public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
            AiAgentRegisterVO agent = agents.get(agentId);
            if (agent == null) {
                throw new NoSuchBeanDefinitionException(agentId);
            }
            return agent;
        }
    }

    private static class StaticObjectProvider implements ObjectProvider<DefaultArmoryFactory> {
        private final DefaultArmoryFactory value;

        private StaticObjectProvider(DefaultArmoryFactory value) {
            this.value = value;
        }

        @Override
        public DefaultArmoryFactory getObject() {
            return value;
        }

        @Override
        public DefaultArmoryFactory getIfAvailable() {
            return value;
        }
    }

    private static class FakeMemoryRepository implements IConversationMemoryRepository {
        private ConversationMemorySnapshotEntity active;

        @Override
        public ConversationMemorySnapshotEntity queryActive(String tenantId, String userId, String sessionId) {
            return active;
        }

        @Override
        public int insert(ConversationMemorySnapshotEntity snapshot) {
            active = snapshot;
            return 1;
        }

        @Override
        public int supersede(String tenantId, String userId, String sessionId, Integer memoryVersion) {
            active = null;
            return 1;
        }

        @Override
        public boolean activate(String tenantId, String userId, String sessionId, Integer expectedMemoryVersion, ConversationMemorySnapshotEntity snapshot) {
            active = snapshot;
            return true;
        }

        @Override
        public ConversationMemorySnapshotEntity invalidateCoveringAndRestore(String tenantId, String userId,
                                                                               String sessionId,
                                                                               Integer minInvalidSequence) {
            active = null;
            return null;
        }
    }

    private static class FakeTaskRepository implements IContextCompactionTaskRepository {
        private ContextCompactionTaskEntity task;

        @Override
        public ContextCompactionTaskEntity createIfAbsent(ContextTaskCreateCommand command) {
            task = ContextCompactionTaskEntity.builder()
                    .taskId("task_1")
                    .tenantId(command.getTenantId())
                    .userId(command.getUserId())
                    .sessionId(command.getSessionId())
                    .fromSequence(command.getFromSequence())
                    .toSequence(command.getToSequence())
                    .expectedMemoryVersion(command.getExpectedMemoryVersion())
                    .policyVersion(command.getPolicyVersion())
                    .status(ContextCompactionTaskStatus.PENDING)
                    .attemptCount(0)
                    .traceId(command.getTraceId())
                    .build();
            return task;
        }

        @Override
        public ContextCompactionTaskEntity queryByTaskId(String taskId) {
            return task;
        }

        @Override
        public List<ContextCompactionTaskEntity> queryUnfinished(String tenantId, String userId, String sessionId) {
            return List.of();
        }

        @Override
        public boolean claim(String taskId) {
            return true;
        }

        @Override
        public int complete(String taskId) {
            return 1;
        }

        @Override
        public int retry(String taskId, String errorMessage) {
            return 1;
        }

        @Override
        public int dead(String taskId, String errorMessage) {
            return 1;
        }

        @Override
        public int staleOverlapping(String tenantId, String userId, String sessionId, String runId,
                                    Integer minSequence, Integer maxSequence, String reason) {
            return 1;
        }
    }

    private static class FakeHistoryRepository implements IContextHistoryRepository {
        private final List<ChatMessageEntity> messages = new ArrayList<>();

        @Override
        public List<ChatMessageEntity> queryMessages(String tenantId, String userId, String sessionId, Integer fromSequenceExclusive, Integer toSequenceInclusive) {
            return messages.stream()
                    .filter(message -> message.getSequenceNo() > fromSequenceExclusive && message.getSequenceNo() <= toSequenceInclusive)
                    .toList();
        }

        @Override
        public int sumEstimatedTokens(String tenantId, String userId, String sessionId, Integer fromSequenceExclusive, Integer toSequenceInclusive) {
            return queryMessages(tenantId, userId, sessionId, fromSequenceExclusive, toSequenceInclusive).stream()
                    .map(ChatMessageEntity::getEstimatedTokenCount)
                    .reduce(0, Integer::sum);
        }
    }

    private static class FakeCacheRepository implements IContextCacheRepository {
        private final List<ChatMessageEntity> recentMessages = new ArrayList<>();
        private boolean recentWindowAvailable;

        @Override
        public ConversationMemorySnapshotEntity queryActiveSnapshot(String tenantId, String userId, String sessionId) {
            return null;
        }

        @Override
        public void cacheActiveSnapshot(ConversationMemorySnapshotEntity snapshot, Duration ttl) {
        }

        @Override
        public void appendRecentMessage(ChatMessageEntity message, int maxMessages, Duration ttl) {
            recentWindowAvailable = true;
            recentMessages.removeIf(item -> item.getSequenceNo().equals(message.getSequenceNo()));
            recentMessages.add(message);
        }

        @Override
        public List<ChatMessageEntity> queryRecentMessages(String tenantId, String userId, String sessionId,
                                                           Integer fromSequenceExclusive, Integer toSequenceInclusive) {
            if (!recentWindowAvailable) {
                return null;
            }
            return recentMessages.stream()
                    .filter(message -> message.getSequenceNo() > fromSequenceExclusive && message.getSequenceNo() <= toSequenceInclusive)
                    .toList();
        }

        @Override
        public void warmRecentMessages(String tenantId, String userId, String sessionId, List<ChatMessageEntity> messages,
                                       int maxMessages, Duration ttl) {
            if (messages != null) {
                messages.forEach(message -> appendRecentMessage(message, maxMessages, ttl));
            }
        }

        @Override
        public void removeRecentMessagesThrough(String tenantId, String userId, String sessionId, Integer coveredToSequence) {
            recentMessages.removeIf(message -> message.getSequenceNo() <= coveredToSequence);
        }

        @Override
        public void evictSession(String tenantId, String userId, String sessionId) {
        }
    }

    private static class FakePublisher implements ContextCompactionPublisher {
        private final List<ContextCompactionCommand> commands = new ArrayList<>();

        @Override
        public void publish(ContextCompactionCommand command) {
            commands.add(command);
        }
    }

    private static class FakeCompressionPort implements ContextCompressionPort {
        @Override
        public String compress(ChatModel chatModel, String prompt) {
            return "{}";
        }
    }
}
