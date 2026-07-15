package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 *
 * 2026/1/20 08:23
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

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
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

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            String userId = trustedUserId(requestDTO.getUserId());
            log.info("智能体对话 agentId:{} workflowId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getWorkflowId(), userId);
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = hasWorkflow(requestDTO)
                        ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                        : chatService.createSession(requestDTO.getAgentId(), userId);
            }

            RunStreamEntity<?> runStream;
            List<String> messages;
            if (hasWorkflow(requestDTO)) {
                RunStreamEntity<String> workflowRun = chatService.startWorkflowMessageTextStream(
                        requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(),
                        userId, sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(),
                        requestDTO.getAttachmentIds());
                runStream = workflowRun;
                messages = workflowRun.getStream().toList().blockingGet();
            } else {
                RunStreamEntity<Event> agentRun = chatService.startMessageStream(requestDTO.getAgentId(), userId,
                        sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(), requestDTO.getAttachmentIds());
                runStream = agentRun;
                messages = agentRun.getStream().map(Event::stringifyContent).toList().blockingGet();
            }

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setSessionId(sessionId);
            responseDTO.setContent(String.join("\n", messages));
            responseDTO.setRunId(runStream.getRun().getRunId());
            responseDTO.setRunStatus("completed");
            responseDTO.setContextRevision(runStream.getRun().getCurrentContextRevision());

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
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), trustedUserId(requestDTO.getUserId()), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        try {
            String userId = trustedUserId(requestDTO.getUserId());
            log.info("流式对话 agentId:{} workflowId:{} modelCode:{} userId:{} sessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getWorkflowId(), requestDTO.getModelCode(), userId, requestDTO.getSessionId(), requestDTO.getMessage());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = hasWorkflow(requestDTO)
                        ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                        : chatService.createSession(requestDTO.getAgentId(), userId);
            }
            emitter.send(SseEmitter.event().name("session").data(sessionId));

            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            String runId = null;
            Disposable disposable;
            if (hasWorkflow(requestDTO)) {
                RunStreamEntity<String> runStream = chatService.startWorkflowMessageTextStream(
                        requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(),
                        userId, sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(),
                        requestDTO.getAttachmentIds());
                runId = runStream.getRun().getRunId();
                emitter.send(SseEmitter.event().name("run").data(java.util.Map.of(
                        "runId", runId,
                        "status", runStream.getRun().getStatus().name().toLowerCase(),
                        "contextRevision", runStream.getRun().getCurrentContextRevision())));
                disposable = subscribeWorkflowTextStream(runStream.getStream(), emitter, disposableRef);
            } else {
                RunStreamEntity<Event> runStream = chatService.startMessageStream(requestDTO.getAgentId(), userId,
                        sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(), requestDTO.getAttachmentIds());
                runId = runStream.getRun().getRunId();
                emitter.send(SseEmitter.event().name("run").data(java.util.Map.of(
                        "runId", runId,
                        "status", runStream.getRun().getStatus().name().toLowerCase(),
                        "contextRevision", runStream.getRun().getCurrentContextRevision())));
                disposable = subscribeAgentEventStream(runStream.getStream(), emitter, disposableRef);
            }
            disposableRef.set(disposable);
            String activeRunId = runId;
            if (activeRunId != null) {
                activeRunRegistry.register(activeRunId, () -> dispose(disposableRef));
            }
            emitter.onCompletion(() -> {
                dispose(disposableRef);
                activeRunRegistry.remove(activeRunId);
            });
            emitter.onTimeout(() -> dispose(disposableRef));
        } catch (Exception e) {
            log.error("流式对话失败", e);
            completeSseWithError(emitter, e);
        }
        return emitter;
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
                                                    AtomicReference<Disposable> disposableRef) {
        return stream.subscribe(
                content -> sendMessage(emitter, disposableRef, content),
                error -> completeSseWithError(emitter, error),
                emitter::complete
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
                                                  AtomicReference<Disposable> disposableRef) {
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        return stream.subscribe(
                event -> sendMessage(emitter, disposableRef, streamDelta(lastContentRef, event.stringifyContent())),
                error -> completeSseWithError(emitter, error),
                emitter::complete
        );
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
        String code = ResponseCode.UN_ERROR.getCode();
        String message = ResponseCode.UN_ERROR.getInfo();
        if (error instanceof AppException appException) {
            code = appException.getCode();
            message = appException.getInfo();
        }
        String safeMessage = message == null || message.isBlank() ? ResponseCode.UN_ERROR.getInfo() : message;
        try {
            // 使用纯文本数据，保证 SSE 已提交后不再经过普通 JSON Response 转换器。
            emitter.send(SseEmitter.event().name("error").data(safeMessage));
        } catch (Exception sendError) {
            log.debug("SSE 错误事件发送失败 code:{}", code, sendError);
        } finally {
            emitter.complete();
        }
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
