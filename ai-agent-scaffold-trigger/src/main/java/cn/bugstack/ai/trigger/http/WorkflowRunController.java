package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.workflow.StaticWorkflowRunResponseDTO;
import cn.bugstack.ai.api.dto.workflow.StaticWorkflowRunStartRequestDTO;
import cn.bugstack.ai.api.dto.workflow.WorkflowRunEventResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.StaticWorkflowStartCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.domain.workflow.service.StaticWorkflowRuntimeService;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.observability.TraceContext;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 普通 DAG 启动及通用工作流节点事件续传入口。 */
@RestController
@RequestMapping("/api/v1/workflow-runs")
public class WorkflowRunController {

    /** 负责创建普通 DAG 运行；运行受理后由后台线程继续推进。 */
    private final StaticWorkflowRuntimeService runtimeService;
    /** 校验运行归属，并从指定事件序号之后继续读取遗漏的工作流事件。 */
    private final WorkflowEventStreamService eventStreamService;

    /**
     * 创建工作流运行接口。
     *
     * @param runtimeService 普通 DAG 运行服务
     * @param eventStreamService 工作流事件续传服务
     */
    public WorkflowRunController(StaticWorkflowRuntimeService runtimeService,
                                 WorkflowEventStreamService eventStreamService) {
        this.runtimeService = runtimeService;
        this.eventStreamService = eventStreamService;
    }

    /** 受理普通 DAG 后立即返回根 Run；后台运行不依赖随后建立的 SSE 连接。 */
    @PostMapping
    public Response<StaticWorkflowRunResponseDTO> start(@RequestBody StaticWorkflowRunStartRequestDTO request) {
        // 租户、用户和角色只能从服务端认证上下文读取，不能接受请求体覆盖。
        ChatRunEntity run = runtimeService.start(StaticWorkflowStartCommandEntity.builder()
                .tenantId(TenantContextHolder.getTenantId()).userId(TenantContextHolder.getUserId())
                .roleCode(TenantContextHolder.getRoleCode()).workflowId(request.getWorkflowId())
                .workflowVersion(request.getWorkflowVersion()).modelCode(request.getModelCode())
                .sessionId(request.getSessionId()).message(request.getMessage()).requestedRunId(request.getRequestedRunId())
                .attachmentIds(request.getAttachmentIds()).build());
        // 返回运行身份和当前状态，调用方随后可使用 runId 建立可续传的事件流。
        return Response.<StaticWorkflowRunResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(StaticWorkflowRunResponseDTO.builder().runId(run.getRunId()).sessionId(run.getSessionId())
                        .workflowId(run.getSourceId()).status(run.getStatus().name()).traceId(run.getTraceId())
                        .operationTraceId(TraceContext.ensureTraceId()).build())
                .build();
    }

    /** 从指定序号续传任意工作流 Run 的持久事件；连接断开不取消后台运行。 */
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(defaultValue = "0") long afterSequence) throws Exception {
        // 先固定当前请求的可信身份，后续响应式回调可能运行在其他线程。
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();
        String operationTraceId = TraceContext.ensureTraceId();
        // 在创建长连接前校验运行归属，避免通过 runId 订阅其他用户的事件。
        ChatRunEntity run = eventStreamService.requireWorkflowRun(tenantId, userId, runId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        // 首帧声明协议版本、运行 Trace 和续传起点，客户端可据此校验后续事件。
        emitter.send(SseEmitter.event().name("STREAM_METADATA").data(Map.of(
                "schemaVersion", WorkflowEventStreamService.SCHEMA_VERSION,
                "runId", runId,
                "traceId", run.getTraceId(),
                "operationTraceId", operationTraceId,
                "afterSequence", Math.max(0, afterSequence))));
        CompositeDisposable subscriptions = new CompositeDisposable();
        // 无论正常结束、超时还是网络错误，都停止事件订阅和心跳定时器。
        emitter.onCompletion(subscriptions::dispose);
        emitter.onTimeout(subscriptions::dispose);
        emitter.onError(error -> subscriptions.dispose());
        // 持久事件流会先补发 afterSequence 之后的历史事件，再持续等待本次运行的新事件。
        Disposable disposable = eventStreamService.stream(tenantId, userId, runId, afterSequence).subscribe(
                event -> sendEvent(emitter, event),
                error -> completeError(emitter, error, run.getTraceId()),
                emitter::complete);
        subscriptions.add(disposable);
        // 空闲期间发送心跳以维持代理连接；心跳不占用业务事件序号。
        subscriptions.add(Flowable.interval(15, 15, TimeUnit.SECONDS).subscribe(
                ignored -> emitter.send(SseEmitter.event().name("heartbeat").data(Map.of(
                        "runId", runId, "traceId", run.getTraceId()))),
                ignored -> subscriptions.dispose()));
        return emitter;
    }

    /**
     * 将领域事件转换为稳定的 SSE 协议事件。
     *
     * @param emitter 当前客户端连接
     * @param event 已持久化且带全局递增序号的工作流事件
     */
    private void sendEvent(SseEmitter emitter, WorkflowRunEventEntity event) {
        try {
            // SSE id 使用持久化序号，客户端重连时可据此计算 afterSequence。
            emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence())).name("workflow_event")
                    .data(WorkflowRunEventResponseDTO.builder().schemaVersion(event.getSchemaVersion())
                            .eventId(event.getEventId()).sequence(event.getSequence()).runId(event.getRunId())
                            .eventType(event.getEventType()).nodeExecutionId(event.getNodeExecutionId())
                            .nodeId(event.getNodeId()).payloadJson(event.getPayloadJson()).traceId(event.getTraceId())
                            .occurredAt(String.valueOf(event.getOccurredAt())).build()));
        } catch (Exception exception) {
            // 写入失败表示连接已不可用；终止订阅，后台工作流仍按原计划运行。
            emitter.completeWithError(exception);
            throw new IllegalStateException("工作流 SSE 连接已关闭", exception);
        }
    }

    /**
     * 向仍可写的连接发送脱敏错误，并结束本次订阅。
     *
     * @param emitter 当前客户端连接
     * @param error 事件读取或订阅失败原因
     * @param traceId 后台工作流运行 Trace ID
     */
    private void completeError(SseEmitter emitter, Throwable error, String traceId) {
        try {
            // 对业务异常保留稳定错误码；其他异常只返回通用码，详细原因留在服务端日志。
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "code", error instanceof cn.bugstack.ai.types.exception.AppException app
                            ? app.getCode() : "WORKFLOW_EVENT_STREAM_FAILED",
                    "message", "工作流事件流失败，请使用 Trace ID 查询详细日志",
                    "traceId", traceId)));
        } catch (Exception ignored) {
            // 连接已关闭时无法再发送错误帧，直接执行完成回调以释放订阅。
        } finally {
            emitter.complete();
        }
    }

}
