package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.workflow.WorkflowRunEventResponseDTO;
import cn.bugstack.ai.api.dto.workflow.IntelligentWorkflowRunStartRequestDTO;
import cn.bugstack.ai.api.dto.workflow.IntelligentWorkflowRunResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRuntimeService;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowStartCommandEntity;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import io.reactivex.rxjava3.disposables.Disposable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 智能工作流持久事件续传入口；连接生命周期不控制工作流运行生命周期。 */
@RestController
@RequestMapping("/api/v1/intelligent-workflow-runs")
public class IntelligentWorkflowRunController {

    private final WorkflowEventStreamService eventStreamService;
    private final IntelligentWorkflowRuntimeService runtimeService;

    public IntelligentWorkflowRunController(WorkflowEventStreamService eventStreamService,
                                            IntelligentWorkflowRuntimeService runtimeService) {
        this.eventStreamService = eventStreamService;
        this.runtimeService = runtimeService;
    }

    /** 受理后立即返回 runId/root traceId；后台运行不依赖随后建立的 SSE 连接。 */
    @PostMapping
    public Response<IntelligentWorkflowRunResponseDTO> start(@RequestBody IntelligentWorkflowRunStartRequestDTO request) {
        IntelligentWorkflowRunEntity run = runtimeService.start(IntelligentWorkflowStartCommandEntity.builder()
                .tenantId(TenantContextHolder.getTenantId()).userId(TenantContextHolder.getUserId())
                .roleCode(TenantContextHolder.getRoleCode()).workflowId(request.getWorkflowId())
                .workflowVersion(request.getWorkflowVersion()).modelCode(request.getModelCode())
                .sessionId(request.getSessionId()).message(request.getMessage()).requestedRunId(request.getRequestedRunId())
                .attachmentIds(request.getAttachmentIds()).build());
        return Response.<IntelligentWorkflowRunResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(IntelligentWorkflowRunResponseDTO.builder().runId(run.getRunId()).workflowId(run.getWorkflowId())
                        .workflowVersion(run.getWorkflowVersion()).status(run.getStatus()).currentNodeId(run.getCurrentNodeId())
                        .traceId(run.getTraceId()).operationTraceId(TraceContext.ensureTraceId())
                        .maxSteps(run.getMaxSteps()).tokenBudget(run.getTokenBudget()).build()).build();
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(defaultValue = "0") long afterSequence) throws Exception {
        String tenantId = TenantContextHolder.getTenantId();
        String userId = TenantContextHolder.getUserId();
        String operationTraceId = TraceContext.ensureTraceId();
        IntelligentWorkflowRunEntity run = eventStreamService.requireRun(tenantId, userId, runId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.send(SseEmitter.event().name("STREAM_METADATA").data(Map.of(
                "schemaVersion", WorkflowEventStreamService.SCHEMA_VERSION,
                "runId", runId,
                "traceId", run.getTraceId(),
                "operationTraceId", operationTraceId,
                "afterSequence", Math.max(0, afterSequence))));
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Disposable disposable = eventStreamService.stream(tenantId, userId, runId, afterSequence).subscribe(
                event -> sendEvent(emitter, event),
                error -> completeError(emitter, error, run.getTraceId()),
                emitter::complete);
        subscription.set(disposable);
        emitter.onCompletion(() -> dispose(subscription));
        emitter.onTimeout(() -> dispose(subscription));
        emitter.onError(error -> dispose(subscription));
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
        }
    }

    private void completeError(SseEmitter emitter, Throwable error, String traceId) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "code", error instanceof cn.bugstack.ai.types.exception.AppException app ? app.getCode() : "WORKFLOW_STREAM_ERROR",
                    "message", error.getMessage() == null ? "工作流事件流失败" : error.getMessage(),
                    "traceId", traceId)));
        } catch (Exception ignored) {
            // 连接已关闭时无需再次写入。
        } finally {
            emitter.complete();
        }
    }

    private void dispose(AtomicReference<Disposable> reference) {
        Disposable disposable = reference.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
    }
}
