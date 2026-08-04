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

    private final StaticWorkflowRuntimeService runtimeService;
    private final WorkflowEventStreamService eventStreamService;

    public WorkflowRunController(StaticWorkflowRuntimeService runtimeService,
                                 WorkflowEventStreamService eventStreamService) {
        this.runtimeService = runtimeService;
        this.eventStreamService = eventStreamService;
    }

    /** 受理普通 DAG 后立即返回根 Run；后台运行不依赖随后建立的 SSE 连接。 */
    @PostMapping
    public Response<StaticWorkflowRunResponseDTO> start(@RequestBody StaticWorkflowRunStartRequestDTO request) {
        ChatRunEntity run = runtimeService.start(StaticWorkflowStartCommandEntity.builder()
                .tenantId(TenantContextHolder.getTenantId()).userId(TenantContextHolder.getUserId())
                .roleCode(TenantContextHolder.getRoleCode()).workflowId(request.getWorkflowId())
                .workflowVersion(request.getWorkflowVersion()).modelCode(request.getModelCode())
                .sessionId(request.getSessionId()).message(request.getMessage()).requestedRunId(request.getRequestedRunId())
                .attachmentIds(request.getAttachmentIds()).build());
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
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();
        String operationTraceId = TraceContext.ensureTraceId();
        ChatRunEntity run = eventStreamService.requireWorkflowRun(tenantId, userId, runId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.send(SseEmitter.event().name("STREAM_METADATA").data(Map.of(
                "schemaVersion", WorkflowEventStreamService.SCHEMA_VERSION,
                "runId", runId,
                "traceId", run.getTraceId(),
                "operationTraceId", operationTraceId,
                "afterSequence", Math.max(0, afterSequence))));
        CompositeDisposable subscriptions = new CompositeDisposable();
        emitter.onCompletion(subscriptions::dispose);
        emitter.onTimeout(subscriptions::dispose);
        emitter.onError(error -> subscriptions.dispose());
        Disposable disposable = eventStreamService.stream(tenantId, userId, runId, afterSequence).subscribe(
                event -> sendEvent(emitter, event),
                error -> completeError(emitter, error, run.getTraceId()),
                emitter::complete);
        subscriptions.add(disposable);
        subscriptions.add(Flowable.interval(15, 15, TimeUnit.SECONDS).subscribe(
                ignored -> emitter.send(SseEmitter.event().name("heartbeat").data(Map.of(
                        "runId", runId, "traceId", run.getTraceId()))),
                ignored -> subscriptions.dispose()));
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, WorkflowRunEventEntity event) {
        try {
            emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence())).name("workflow_event")
                    .data(WorkflowRunEventResponseDTO.builder().schemaVersion(event.getSchemaVersion())
                            .eventId(event.getEventId()).sequence(event.getSequence()).runId(event.getRunId())
                            .eventType(event.getEventType()).nodeExecutionId(event.getNodeExecutionId())
                            .nodeId(event.getNodeId()).payloadJson(event.getPayloadJson()).traceId(event.getTraceId())
                            .occurredAt(String.valueOf(event.getOccurredAt())).build()));
        } catch (Exception exception) {
            emitter.completeWithError(exception);
            throw new IllegalStateException("工作流 SSE 连接已关闭", exception);
        }
    }

    private void completeError(SseEmitter emitter, Throwable error, String traceId) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "code", error instanceof cn.bugstack.ai.types.exception.AppException app
                            ? app.getCode() : "WORKFLOW_EVENT_STREAM_FAILED",
                    "message", "工作流事件流失败，请使用 Trace ID 查询详细日志",
                    "traceId", traceId)));
        } catch (Exception ignored) {
            // 连接已关闭时无需再次写入。
        } finally {
            emitter.complete();
        }
    }

}
