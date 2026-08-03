package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.Predicate;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.ReplayProcessor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 持久化业务事件并提供有界实时桥；SSE 断开只结束订阅，不取消后台运行。 */
@Service
public class WorkflowEventStreamService {

    public static final String SCHEMA_VERSION = "workflow-event-v1";
    private static final int REPLAY_LIMIT = 1000;
    private static final int LIVE_BUFFER_SIZE = 1024;

    private final IWorkflowEventRepository eventRepository;
    private final IIntelligentWorkflowRunRepository runRepository;
    private final ConcurrentMap<String, FlowableProcessor<WorkflowRunEventEntity>> liveStreams = new ConcurrentHashMap<>();

    public WorkflowEventStreamService(IWorkflowEventRepository eventRepository,
                                      IIntelligentWorkflowRunRepository runRepository) {
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
    }

    /** 先提交数据库，再通知本实例订阅者；通知丢失可由 sequence 续传补齐。 */
    public WorkflowRunEventEntity publish(String tenantId, String userId, String runId, String traceId,
                                          String eventType, String nodeExecutionId, String nodeId,
                                          String payloadJson) {
        requireText(eventType, "事件类型不能为空");
        WorkflowRunEventEntity event = WorkflowRunEventEntity.builder()
                .tenantId(tenantId).userId(userId).runId(runId)
                .eventId("wfe_" + UUID.randomUUID()).schemaVersion(SCHEMA_VERSION).eventType(eventType)
                .nodeExecutionId(nodeExecutionId).nodeId(nodeId).payloadJson(payloadJson == null ? "{}" : payloadJson)
                .traceId(traceId).occurredAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(30)).build();
        WorkflowRunEventEntity stored = eventRepository.append(event);
        processor(key(tenantId, runId)).onNext(stored);
        return stored;
    }

    /** 返回历史+实时同一序列；ReplayProcessor 在历史查询期间兜住本实例刚提交的事件。 */
    public Flowable<WorkflowRunEventEntity> stream(String tenantId, String userId, String runId, long afterSequence) {
        IntelligentWorkflowRunEntity run = requireRun(tenantId, userId, runId);
        long safeAfter = Math.max(0, afterSequence);
        Long oldest = eventRepository.queryOldestSequence(tenantId, userId, runId);
        if (safeAfter > 0 && oldest != null && safeAfter < oldest - 1) {
            throw new AppException("WORKFLOW_EVENT_HISTORY_EXPIRED", "请求的事件历史已过保留期，请读取最终快照");
        }
        FlowableProcessor<WorkflowRunEventEntity> live = processor(key(tenantId, runId));
        List<WorkflowRunEventEntity> history = eventRepository.queryAfter(tenantId, userId, runId, safeAfter, REPLAY_LIMIT);
        AtomicLong cursor = new AtomicLong(safeAfter);
        return Flowable.concat(Flowable.fromIterable(history), live.onBackpressureBuffer(LIVE_BUFFER_SIZE, false, true))
                .filter(event -> run.getTraceId().equals(event.getTraceId()))
                .filter(event -> event.getSequence() != null && event.getSequence() > cursor.get())
                .doOnNext(event -> cursor.set(event.getSequence()))
                // 终态事件本身必须交付；交付后由服务端正常结束 SSE，释放订阅和浏览器连接。
                .takeUntil((Predicate<WorkflowRunEventEntity>) event -> terminal(event.getEventType()));
    }

    public IntelligentWorkflowRunEntity requireRun(String tenantId, String userId, String runId) {
        IntelligentWorkflowRunEntity run = runRepository.query(tenantId, userId, runId);
        if (run == null) {
            throw new AppException("WORKFLOW_RUN_NOT_FOUND", "智能工作流运行不存在或无权访问");
        }
        return run;
    }

    private FlowableProcessor<WorkflowRunEventEntity> processor(String key) {
        return liveStreams.computeIfAbsent(key,
                ignored -> ReplayProcessor.<WorkflowRunEventEntity>createWithSize(LIVE_BUFFER_SIZE).toSerialized());
    }

    private String key(String tenantId, String runId) {
        return tenantId + "\u0000" + runId;
    }

    private boolean terminal(String eventType) {
        return "WORKFLOW_COMPLETED".equals(eventType)
                || "WORKFLOW_FAILED".equals(eventType)
                || "WORKFLOW_CANCELLED".equals(eventType);
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new AppException("WORKFLOW_EVENT_INVALID", message);
    }
}
