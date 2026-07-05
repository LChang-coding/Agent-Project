package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private SessionDomain sessionDomain;

    /**
     * 查询 Agent 配置；无参数；返回当前可用 Agent 列表。
     */
    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    /**
     * 创建会话；参数是 Agent ID 和用户ID；返回平台会话ID。
     */
    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService().createSession(appName, userId).blockingGet();
        CreateSessionCommandEntity command = new CreateSessionCommandEntity();
        command.setTenantId(currentTenantId());
        command.setUserId(userId);
        command.setSessionId(session.id());
        command.setAgentId(agentId);
        command.setAgentName(aiAgentRegisterVO.getAgentName());
        command.setAppName(appName);
        command.setTitle(aiAgentRegisterVO.getAgentName());
        sessionDomain.createSession(command);
        return session.id();
    }

    /**
     * 发送消息；参数是 Agent ID、用户ID和消息；返回模型回复列表。
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    /**
     * 发送消息；参数是 Agent ID、用户ID、会话ID和消息；返回模型回复列表。
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(agentId, userId, sessionId);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, agentId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, actualSessionId);
        String traceId = TraceContext.currentOrNewTraceId();
        sessionDomain.appendUserMessage(tenantId, userId, actualSessionId, message, traceId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, actualSessionId, userMsg, RunConfig.builder().build(), traceStateDelta());

        List<String> outputs = new ArrayList<>();
        try {
            events.blockingForEach(event -> outputs.add(event.stringifyContent()));
            saveAssistantMessage(tenantId, userId, actualSessionId, String.join("\n", outputs), traceId);
        } catch (RuntimeException e) {
            saveAssistantErrorMessage(tenantId, userId, actualSessionId, traceId, e, String.join("\n", outputs));
            throw e;
        }

        return outputs;
    }

    /**
     * 流式发送消息；参数是 Agent ID、用户ID、会话ID和消息；返回事件流。
     */
    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(agentId, userId, sessionId);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, agentId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, actualSessionId);
        String traceId = TraceContext.currentOrNewTraceId();
        sessionDomain.appendUserMessage(tenantId, userId, actualSessionId, message, traceId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean assistantSaved = new AtomicBoolean(false);
        return runner.runAsync(userId, actualSessionId, userMsg, runConfig, traceStateDelta())
                .doOnNext(event -> appendContent(assistantContent, event.stringifyContent()))
                .doOnComplete(() -> saveAssistantMessageOnce(assistantSaved, tenantId, userId, actualSessionId, assistantContent.toString(), traceId))
                .doOnError(throwable -> saveAssistantErrorMessageOnce(assistantSaved, tenantId, userId, actualSessionId, traceId, throwable, assistantContent.toString()))
                .doOnCancel(() -> saveAssistantErrorMessageOnce(assistantSaved, tenantId, userId, actualSessionId, traceId,
                        new IllegalStateException("stream_cancelled"), assistantContent.toString()));
    }

    /**
     * 发送复合消息；参数是聊天命令；返回模型回复列表。
     */
    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(chatCommandEntity.getAgentId(), chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());
        sessionDomain.assertSessionAccess(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId());

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), chatCommandEntity.getUserId(), actualSessionId);
        String traceId = TraceContext.currentOrNewTraceId();
        sessionDomain.appendUserMessage(tenantId, chatCommandEntity.getUserId(), actualSessionId, describeContent(chatCommandEntity), traceId);

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), actualSessionId, content, RunConfig.builder().build(), traceStateDelta());

        List<String> outputs = new ArrayList<>();
        try {
            events.blockingForEach(event -> outputs.add(event.stringifyContent()));
            saveAssistantMessage(tenantId, chatCommandEntity.getUserId(), actualSessionId, String.join("\n", outputs), traceId);
        } catch (RuntimeException e) {
            saveAssistantErrorMessage(tenantId, chatCommandEntity.getUserId(), actualSessionId, traceId, e, String.join("\n", outputs));
            throw e;
        }

        return outputs;
    }

    /**
     * 获取链路状态；无参数；返回传给 ADK 的 trace 状态。
     */
    private Map<String, Object> traceStateDelta() {
        return Map.of(TraceContext.TRACE_ID_STATE_KEY, TraceContext.currentOrNewTraceId());
    }

    /**
     * 确保会话ID存在；参数是 Agent ID、用户ID和会话ID；返回可用会话ID。
     */
    private String ensureSessionId(String agentId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, userId);
        }
        return sessionId;
    }

    /**
     * 确保 ADK 内存会话存在；参数是 Runner、应用、用户和会话ID；无返回值。
     */
    private void ensureAdkSession(InMemoryRunner runner, String appName, String userId, String sessionId) {
        Session session = runner.sessionService()
                .getSession(appName, userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            runner.sessionService()
                    .createSession(appName, userId, new ConcurrentHashMap<>(), sessionId)
                    .blockingGet();
        }
    }

    /**
     * 保存助手消息；参数是租户、用户、会话、内容和链路ID；无返回值。
     */
    private void saveAssistantMessage(String tenantId, String userId, String sessionId, String content, String traceId) {
        if (content != null && !content.isBlank()) {
            sessionDomain.appendAssistantMessage(tenantId, userId, sessionId, content, traceId);
        }
    }

    /**
     * 只保存一次助手消息；参数是保存标记、身份、内容和链路ID；无返回值。
     */
    private void saveAssistantMessageOnce(AtomicBoolean saved,
                                          String tenantId,
                                          String userId,
                                          String sessionId,
                                          String content,
                                          String traceId) {
        if (saved.compareAndSet(false, true)) {
            saveAssistantMessage(tenantId, userId, sessionId, content, traceId);
        }
    }

    /**
     * 保存助手错误消息；参数是身份、链路ID、异常和已生成内容；无返回值。
     */
    private void saveAssistantErrorMessage(String tenantId,
                                           String userId,
                                           String sessionId,
                                           String traceId,
                                           Throwable throwable,
                                           String partialContent) {
        sessionDomain.appendAssistantMessage(tenantId, userId, sessionId, errorContent(throwable, partialContent), traceId);
    }

    /**
     * 只保存一次助手错误消息；参数是保存标记、身份、异常和已生成内容；无返回值。
     */
    private void saveAssistantErrorMessageOnce(AtomicBoolean saved,
                                               String tenantId,
                                               String userId,
                                               String sessionId,
                                               String traceId,
                                               Throwable throwable,
                                               String partialContent) {
        if (saved.compareAndSet(false, true)) {
            saveAssistantErrorMessage(tenantId, userId, sessionId, traceId, throwable, partialContent);
        }
    }

    /**
     * 构造错误内容；参数是异常和已生成内容；返回可落库文本。
     */
    private String errorContent(Throwable throwable, String partialContent) {
        String errorType = throwable == null ? "UnknownError" : throwable.getClass().getSimpleName();
        String errorMessage = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
        String partial = partialContent == null || partialContent.isBlank() ? "" : "\npartialContent=" + partialContent;
        return "[assistant_error] type=" + errorType + " message=" + errorMessage + partial;
    }

    /**
     * 追加流式内容；参数是内容缓冲和分片；无返回值。
     */
    private void appendContent(StringBuilder assistantContent, String content) {
        if (content != null && !content.isBlank()) {
            assistantContent.append(content);
        }
    }

    /**
     * 描述复合消息；参数是聊天命令；返回可落库文本。
     */
    private String describeContent(ChatCommandEntity chatCommandEntity) {
        List<String> contentList = new ArrayList<>();
        if (chatCommandEntity.getTexts() != null) {
            chatCommandEntity.getTexts().forEach(text -> contentList.add(text.getMessage()));
        }
        if (chatCommandEntity.getFiles() != null) {
            chatCommandEntity.getFiles().forEach(file -> contentList.add("[file] " + file.getFileUri()));
        }
        if (chatCommandEntity.getInlineDatas() != null) {
            chatCommandEntity.getInlineDatas().forEach(inlineData -> contentList.add("[inline_data] " + inlineData.getMimeType()));
        }
        return String.join("\n", contentList);
    }

    /**
     * 获取当前租户ID；无参数；返回租户ID。
     */
    private String currentTenantId() {
        return TenantContextHolder.getTenantId();
    }

}
