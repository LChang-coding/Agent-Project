package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.entity.SessionOrchestrationSnapshotEntity;
import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.domain.agent.service.SubagentOrchestrationService;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 会话级 Multi-Agent 运行快照与增量事件流。 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/orchestration")
public class SessionOrchestrationController {
    private final SessionOrchestrationQueryService queryService;
    private final SessionDomain sessionDomain;
    private final SubagentOrchestrationService orchestrationService;

    public SessionOrchestrationController(SessionOrchestrationQueryService queryService, SessionDomain sessionDomain,
                                          SubagentOrchestrationService orchestrationService) {
        this.queryService = queryService;
        this.sessionDomain = sessionDomain;
        this.orchestrationService = orchestrationService;
    }

    /** 仅允许取消当前用户会话快照中仍可执行的子任务。 */
    @PostMapping("/tasks/{taskId}/cancel")
    public Response<Map<String, Object>> cancelTask(@PathVariable String sessionId, @PathVariable String taskId) {
        String tenantId = TenantContextHolder.getTenantId(), userId = requireUser();
        sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        SessionOrchestrationSnapshotEntity snapshot = queryService.query(tenantId, userId, sessionId);
        SessionOrchestrationSnapshotEntity.Run owner = snapshot.getRuns().stream()
                .filter(run -> run.getTasks().stream().anyMatch(task -> taskId.equals(task.getTaskId())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("子 Agent 任务不存在"));
        int changed = orchestrationService.cancel(tenantId, owner.getParentRunId(), List.of(taskId));
        return Response.<Map<String, Object>>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(Map.of("taskId", taskId, "cancelled", changed == 1)).build();
    }

    /** 完整输出按任务读取，避免状态 SSE 重复推送大文本。 */
    @GetMapping("/tasks/{taskId}")
    public Response<SessionOrchestrationSnapshotEntity.Task> taskDetail(
            @PathVariable String sessionId, @PathVariable String taskId) {
        String tenantId = TenantContextHolder.getTenantId(), userId = requireUser();
        sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        SessionOrchestrationSnapshotEntity snapshot = queryService.query(tenantId, userId, sessionId);
        SessionOrchestrationSnapshotEntity.Run owner = snapshot.getRuns().stream()
                .filter(run -> run.getTasks().stream().anyMatch(task -> taskId.equals(task.getTaskId())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("子 Agent 任务不存在"));
        SessionOrchestrationSnapshotEntity.Task value = queryService.queryTask(tenantId, userId, sessionId,
                owner.getParentRunId(), taskId);
        return Response.<SessionOrchestrationSnapshotEntity.Task>builder()
                .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(value).build();
    }

    @GetMapping
    public Response<SessionOrchestrationSnapshotEntity> snapshot(@PathVariable String sessionId) {
        String tenantId = TenantContextHolder.getTenantId(), userId = requireUser();
        sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        return Response.<SessionOrchestrationSnapshotEntity>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(queryService.query(tenantId, userId, sessionId)).build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId,
                             @RequestParam(required = false) String afterVersion) throws Exception {
        String tenantId = TenantContextHolder.getTenantId(), userId = requireUser();
        sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        AtomicReference<String> version = new AtomicReference<>(afterVersion == null ? "" : afterVersion);
        emitter.send(SseEmitter.event().name("STREAM_METADATA")
                .data(Map.of("schemaVersion", "session-orchestration-v1", "sessionId", sessionId)));
        Disposable subscription = Flowable.interval(0, 1, TimeUnit.SECONDS).subscribe(ignored -> {
            SessionOrchestrationSnapshotEntity value = queryService.query(tenantId, userId, sessionId);
            if (!value.getVersion().equals(version.getAndSet(value.getVersion()))) {
                emitter.send(SseEmitter.event().id(value.getVersion()).name("orchestration_snapshot").data(value));
            } else if (ignored > 0 && ignored % 15 == 0) {
                emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("version", version.get())));
            }
        }, emitter::completeWithError);
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(error -> subscription.dispose());
        return emitter;
    }

    private String requireUser() {
        String userId = TenantContextHolder.getUserId();
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户身份不能为空");
        return userId;
    }
}
