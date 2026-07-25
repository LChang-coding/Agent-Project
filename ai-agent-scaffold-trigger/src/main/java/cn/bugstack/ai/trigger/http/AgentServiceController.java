package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationMetadataService;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Agent 与数据库工作流的会话创建、同步对话和 SSE 对话入口。
 * <p>控制器负责协议分流和事件输出；消息持久化、上下文、RAG、工具和 DAG 执行全部交给领域服务。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private ActiveRunRegistry activeRunRegistry;

    @Resource
    private RagAnswerCitationMetadataService citationMetadataService;

    /** 查询当前租户可用的静态 Agent 配置。 */
    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 创建 Agent 或工作流会话。
     * <p>存在 workflowId 时绑定工作流版本，否则按 agentId 创建普通 Agent 会话。</p>
     */
    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            // 优先使用 JWT 用户，request.userId 仅保留给旧兼容调用。
            String userId = trustedUserId(requestDTO.getUserId());
            log.info("创建会话 agentId:{} workflowId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getWorkflowId(), userId);
            String sessionId = hasWorkflow(requestDTO)
                    ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                    : chatService.createSession(requestDTO.getAgentId(), userId);

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), trustedUserId(requestDTO.getUserId()), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /** 兼容旧 GET 调用并复用 POST 创建逻辑。 */
    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    /**
     * 执行一次同步对话并等待完整最终结果。
     * <p>工作流消费文本结果，普通 Agent 消费 ADK Event；两者都通过 runId 关联取消、用量和引用。</p>
     */
    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            String userId = trustedUserId(requestDTO.getUserId());
            log.info("智能体对话 agentId:{} workflowId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getWorkflowId(), userId);
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                // 未提供会话时先建立服务端会话，后续消息和运行都以该 sessionId 隔离。
                sessionId = hasWorkflow(requestDTO)
                        ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                        : chatService.createSession(requestDTO.getAgentId(), userId);
            }

            RunStreamEntity<?> runStream;
            List<String> messages;
            boolean workflowRequest = hasWorkflow(requestDTO);
            if (workflowRequest) {
                // ChatService 保存 message 后将其作为 DAG 输入，附件ID也在领域层校验并绑定。
                RunStreamEntity<String> workflowRun = chatService.startWorkflowMessageTextStream(
                        requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(),
                        userId, sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(),
                        requestDTO.getAttachmentIds());
                runStream = workflowRun;
                messages = workflowRun.getStream().toList().blockingGet();
            } else {
                // 普通 Agent 由 ChatService 创建 Run，再把 message 交给 ADK Runner 分析。
                RunStreamEntity<Event> agentRun = chatService.startMessageStream(requestDTO.getAgentId(), userId,
                        sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(), requestDTO.getAttachmentIds());
                runStream = agentRun;
                messages = agentRun.getStream().map(Event::stringifyContent).toList().blockingGet();
            }

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setSessionId(sessionId);
            responseDTO.setContent(workflowRequest ? String.join("\n", messages) : mergeAgentContents(messages));
            responseDTO.setRunId(runStream.getRun().getRunId());
            responseDTO.setRunStatus("completed");
            responseDTO.setContextRevision(runStream.getRun().getCurrentContextRevision());
            // 最终回答提交后再读取引用快照，确保响应引用与数据库消息一致。
            applyCitationSnapshot(responseDTO, citationMetadataService.queryRunAnswer(
                    TenantContextHolder.getTenantId(), userId, sessionId, runStream.getRun().getRunId()));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话失败 agentId:{} userId:{}", requestDTO.getAgentId(), trustedUserId(requestDTO.getUserId()), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 创建 SSE 对话流。
     * <p>先发送 trace/session/run 元数据，再发送 message 增量和引用终态；客户端可立即使用 runId 取消。</p>
     */
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        // Trace 必须在任何业务事件前确定并返回，用户才能据此查询完整链路日志。
        String traceId = TraceContext.ensureTraceId();
        try {
            sendTraceMetadata(emitter, traceId);
            String userId = trustedUserId(requestDTO.getUserId());
            log.info("流式对话已受理 agentId:{} workflowId:{} modelCode:{} userId:{} sessionId:{} messageLength:{} attachmentCount:{}",
                    requestDTO.getAgentId(), requestDTO.getWorkflowId(), requestDTO.getModelCode(), userId,
                    requestDTO.getSessionId(), requestDTO.getMessage() == null ? 0 : requestDTO.getMessage().length(),
                    requestDTO.getAttachmentIds() == null ? 0 : requestDTO.getAttachmentIds().size());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = hasWorkflow(requestDTO)
                        ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                        : chatService.createSession(requestDTO.getAgentId(), userId);
            }
            emitter.send(SseEmitter.event().name("session").data(sessionId));

            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            AtomicBoolean interruptRequested = new AtomicBoolean(false);
            String runId = null;
            if (hasWorkflow(requestDTO)) {
                // 创建 Run 后先注册取消句柄，再订阅执行流，封闭立即取消的竞态窗口。
                RunStreamEntity<String> runStream = chatService.startWorkflowMessageTextStream(
                        requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(),
                        userId, sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(),
                        requestDTO.getAttachmentIds());
                runId = runStream.getRun().getRunId();
                registerActiveStream(runId, emitter, disposableRef, interruptRequested);
                emitter.send(SseEmitter.event().name("run").data(java.util.Map.of(
                        "runId", runId,
                        "status", runStream.getRun().getStatus().name().toLowerCase(),
                        "contextRevision", runStream.getRun().getCurrentContextRevision(),
                        "traceId", traceId)));
                if (!interruptRequested.get()) {
                    attachDisposable(disposableRef, interruptRequested,
                            subscribeWorkflowTextStream(runStream.getStream(), emitter, disposableRef,
                                    TenantContextHolder.getTenantId(), userId, sessionId, runId, traceId));
                }
            } else {
                RunStreamEntity<Event> runStream = chatService.startMessageStream(requestDTO.getAgentId(), userId,
                        sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(), requestDTO.getAttachmentIds());
                runId = runStream.getRun().getRunId();
                registerActiveStream(runId, emitter, disposableRef, interruptRequested);
                emitter.send(SseEmitter.event().name("run").data(java.util.Map.of(
                        "runId", runId,
                        "status", runStream.getRun().getStatus().name().toLowerCase(),
                        "contextRevision", runStream.getRun().getCurrentContextRevision(),
                        "traceId", traceId)));
                if (!interruptRequested.get()) {
                    attachDisposable(disposableRef, interruptRequested,
                            subscribeAgentEventStream(runStream.getStream(), emitter, disposableRef,
                                    TenantContextHolder.getTenantId(), userId, sessionId, runId, traceId));
                }
            }
        } catch (Exception e) {
            log.error("流式对话失败", e);
            completeSseWithError(emitter, e, traceId);
        }
        return emitter;
    }

    /** 在run事件发送前建立取消句柄，避免客户立即取消丢信号。 */
    private void registerActiveStream(String runId, SseEmitter emitter,
                                      AtomicReference<Disposable> disposableRef,
                                      AtomicBoolean interruptRequested) {
        if (runId == null) return;
        activeRunRegistry.register(runId, () -> {
            if (interruptRequested.compareAndSet(false, true)) {
                dispose(disposableRef);
                emitter.complete();
            }
        });
        emitter.onCompletion(() -> {
            dispose(disposableRef);
            activeRunRegistry.remove(runId);
        });
        emitter.onTimeout(() -> {
            if (!activeRunRegistry.interrupt(runId)) {
                dispose(disposableRef);
                emitter.complete();
            }
        });
        emitter.onError(error -> {
            dispose(disposableRef);
            activeRunRegistry.remove(runId);
        });
    }

    /** 发布订阅句柄并补偿注册与取消交错的竞态。 */
    private void attachDisposable(AtomicReference<Disposable> disposableRef,
                                  AtomicBoolean interruptRequested,
                                  Disposable disposable) {
        disposableRef.set(disposable);
        if (interruptRequested.get()) {
            dispose(disposableRef);
        }
    }

    /**
     * 订阅工作流文本流；参数是请求、用户、会话、SSE 和订阅引用；返回订阅句柄。
     */
    private Disposable subscribeWorkflowTextStream(ChatRequestDTO requestDTO,
                                                   String userId,
                                                   String sessionId,
                                                   SseEmitter emitter,
                                                   AtomicReference<Disposable> disposableRef) {
        return chatService.handleWorkflowMessageTextStream(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId, sessionId, requestDTO.getMessage())
                .subscribe(
                        content -> sendMessage(emitter, disposableRef, content),
                        error -> completeSseWithError(emitter, error),
                        emitter::complete
                );
    }

    /**
     * 订阅已创建的工作流文本流；参数是文本流、SSE 和订阅引用；返回订阅句柄。
     */
    private Disposable subscribeWorkflowTextStream(Flowable<String> stream,
                                                    SseEmitter emitter,
                                                    AtomicReference<Disposable> disposableRef,
                                                    String tenantId, String userId, String sessionId, String runId,
                                                    String traceId) {
        return stream.subscribe(
                content -> sendMessage(emitter, disposableRef, content),
                error -> completeSseWithError(emitter, error, traceId),
                () -> completeSseWithCitation(emitter, tenantId, userId, sessionId, runId, traceId)
        );
    }

    /**
     * 订阅普通 Agent 事件流；参数是请求、用户、会话、SSE 和订阅引用；返回订阅句柄。
     */
    private Disposable subscribeAgentEventStream(ChatRequestDTO requestDTO,
                                                 String userId,
                                                 String sessionId,
                                                 SseEmitter emitter,
                                                 AtomicReference<Disposable> disposableRef) {
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        return chatService.handleMessageStream(requestDTO.getAgentId(), userId, sessionId, requestDTO.getMessage())
                .subscribe(
                        event -> sendMessage(emitter, disposableRef, streamDelta(lastContentRef, event.stringifyContent())),
                        error -> completeSseWithError(emitter, error),
                        emitter::complete
                );
    }

    /**
     * 订阅已创建的 Agent 运行流；参数是事件流、SSE 和订阅引用；返回订阅句柄。
     */
    private Disposable subscribeAgentEventStream(Flowable<Event> stream,
                                                  SseEmitter emitter,
                                                  AtomicReference<Disposable> disposableRef,
                                                  String tenantId, String userId, String sessionId, String runId,
                                                  String traceId) {
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        return stream.subscribe(
                event -> sendMessage(emitter, disposableRef, streamDelta(lastContentRef, event.stringifyContent())),
                error -> completeSseWithError(emitter, error, traceId),
                () -> completeSseWithCitation(emitter, tenantId, userId, sessionId, runId, traceId)
        );
    }

    /** 在业务事务已提交后发送唯一引用终态事件。 */
    private void completeSseWithCitation(SseEmitter emitter, String tenantId, String userId,
                                         String sessionId, String runId) {
        completeSseWithCitation(emitter, tenantId, userId, sessionId, runId, TraceContext.ensureTraceId());
    }

    /** 查询最终回答引用快照，发送唯一引用终态事件后关闭 SSE。 */
    private void completeSseWithCitation(SseEmitter emitter, String tenantId, String userId,
                                         String sessionId, String runId, String traceId) {
        try {
            RagAnswerCitationMetadataService.AnswerSnapshot snapshot = citationMetadataService.queryRunAnswer(
                    tenantId, userId, sessionId, runId);
            if (snapshot != null) {
                emitter.send(SseEmitter.event().name("citation_validation").data(java.util.Map.of(
                        "messageId", snapshot.messageId(), "runId", runId,
                        "validation", toCitationDTO(snapshot.validation()))));
            }
            emitter.complete();
        } catch (Exception exception) {
            completeSseWithError(emitter, exception, traceId);
        }
    }

    /** 把持久化引用快照附加到同步聊天响应。 */
    private void applyCitationSnapshot(ChatResponseDTO response,
                                       RagAnswerCitationMetadataService.AnswerSnapshot snapshot) {
        if (snapshot == null) return;
        response.setMessageId(snapshot.messageId());
        response.setCitationValidation(toCitationDTO(snapshot.validation()));
    }

    /** 将领域引用校验结果转换为 API DTO。 */
    private RagCitationValidationDTO toCitationDTO(RagAnswerCitationValidation value) {
        return RagCitationValidationDTO.builder().status(value.status().name())
                .retrievalIds(value.retrievalIds()).allowedCitationIds(value.allowedCitationIds())
                .usedCitationIds(value.usedCitationIds()).invalidCitationIds(value.invalidCitationIds())
                .citations(value.usedCitations().stream().map(citation -> RagCitationValidationDTO.CitationDTO.builder()
                        .citationId(citation.citationId()).knowledgeBaseId(citation.knowledgeBaseId())
                        .documentId(citation.documentId()).documentName(citation.documentName())
                        .versionId(citation.versionId()).documentVersion(citation.documentVersion())
                        .generation(citation.generation()).chunkId(citation.chunkId())
                        .pageNumber(citation.pageNumber()).headingPath(citation.headingPath()).build()).toList())
                .build();
    }

    /**
     * 发送 SSE 消息；参数是 SSE、订阅引用和内容；无返回值。
     */
    private void sendMessage(SseEmitter emitter, AtomicReference<Disposable> disposableRef, String content) {
        try {
            if (content != null && !content.isBlank()) {
                emitter.send(SseEmitter.event().name("message").data(content));
            }
        } catch (Exception e) {
            log.error("流式对话发送失败", e);
            dispose(disposableRef);
            emitter.completeWithError(e);
        }
    }

    /**
     * 将流式业务异常编码成合法 SSE error 事件；参数是响应和异常；无返回值。
     */
    private void completeSseWithError(SseEmitter emitter, Throwable error) {
        completeSseWithError(emitter, error, TraceContext.ensureTraceId());
    }

    /** 将异常安全编码为 SSE error 事件，并始终返回 traceId。 */
    private void completeSseWithError(SseEmitter emitter, Throwable error, String traceId) {
        String code = ResponseCode.UN_ERROR.getCode();
        String message = ResponseCode.UN_ERROR.getInfo();
        if (error instanceof AppException appException) {
            code = appException.getCode();
            message = appException.getInfo();
        }
        String safeMessage = message == null || message.isBlank() ? ResponseCode.UN_ERROR.getInfo() : message;
        try {
            emitter.send(SseEmitter.event().name("error").data(java.util.Map.of(
                    "code", code,
                    "message", safeMessage,
                    "traceId", traceId)));
        } catch (Exception sendError) {
            log.debug("SSE 错误事件发送失败 code:{}", code, sendError);
        } finally {
            emitter.complete();
        }
    }

    /**
     * 在业务事件之前发送本次请求的链路标识；参数是 SSE 和链路标识；无返回值。
     */
    private void sendTraceMetadata(SseEmitter emitter, String traceId) throws java.io.IOException {
        emitter.send(SseEmitter.event().name("trace").data(java.util.Map.of("traceId", traceId)));
    }

    /**
     * 计算流式增量；参数是上一段内容引用和当前事件内容；返回本次应发送的文本。
     */
    private String streamDelta(AtomicReference<String> lastContentRef, String currentContent) {
        if (currentContent == null || currentContent.isBlank()) {
            return "";
        }
        String lastContent = lastContentRef.get();
        if (currentContent.equals(lastContent)) {
            return "";
        }
        if (lastContent != null && !lastContent.isBlank() && currentContent.startsWith(lastContent)) {
            lastContentRef.set(currentContent);
            return currentContent.substring(lastContent.length());
        }
        lastContentRef.set(lastContent == null ? currentContent : lastContent + currentContent);
        return currentContent;
    }

    /**
     * 合并 Agent 的累计流事件；参数是每个事件的完整内容；返回不重复的最终文本。
     */
    private String mergeAgentContents(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        StringBuilder merged = new StringBuilder();
        for (String content : contents) {
            merged.append(streamDelta(lastContentRef, content));
        }
        return merged.toString();
    }

    /**
     * 读取可信用户身份。
     * <p>已认证请求使用 JWT 用户；仅在认证上下文缺失时兼容旧接口传入的 userId。</p>
     */
    private String trustedUserId(String requestUserId) {
        String userId = TenantContextHolder.getUserId();
        return userId == null || userId.isBlank() ? requestUserId : userId;
    }

    /**
     * 判断是否使用数据库工作流；参数是聊天请求；返回是否包含工作流ID。
     */
    private boolean hasWorkflow(ChatRequestDTO requestDTO) {
        return requestDTO != null && requestDTO.getWorkflowId() != null && !requestDTO.getWorkflowId().isBlank();
    }

    /**
     * 判断是否使用数据库工作流；参数是创建会话请求；返回是否包含工作流ID。
     */
    private boolean hasWorkflow(CreateSessionRequestDTO requestDTO) {
        return requestDTO != null && requestDTO.getWorkflowId() != null && !requestDTO.getWorkflowId().isBlank();
    }

    /**
     * 释放流式订阅；参数是订阅引用；无返回值。
     */
    private void dispose(AtomicReference<Disposable> disposableRef) {
        Disposable disposable = disposableRef.get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

}
