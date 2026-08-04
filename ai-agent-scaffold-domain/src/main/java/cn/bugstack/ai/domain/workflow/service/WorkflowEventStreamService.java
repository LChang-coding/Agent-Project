package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.Predicate;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** 持久化业务事件并提供有界实时桥；SSE 断开只结束订阅，不取消后台运行。 */
@Service
public class WorkflowEventStreamService {

    public static final String SCHEMA_VERSION = "workflow-event-v1";
    private static final int REPLAY_LIMIT = 1000;
    private static final long DATABASE_TAIL_INTERVAL_MILLIS = 1000L;

    private final IWorkflowEventRepository eventRepository;
    private final IIntelligentWorkflowRunRepository runRepository;
    private final IChatRunRepository chatRunRepository;
    private final FlowableProcessor<String> wakeups = PublishProcessor.<String>create().toSerialized();

    public WorkflowEventStreamService(IWorkflowEventRepository eventRepository,
                                      IIntelligentWorkflowRunRepository runRepository,
                                      IChatRunRepository chatRunRepository) {
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.chatRunRepository = chatRunRepository;
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
        try {
            WorkflowRunEventEntity stored = eventRepository.append(event);
            notifyAfterCommit(key(tenantId, runId));
            return stored;
        } catch (AppException exception) {
            // 完成、失败和取消可能竞争；数据库唯一终态槽位决定胜者，迟到分支直接复用已落库终态。
            if (terminal(eventType) && "WORKFLOW_EVENT_AFTER_TERMINAL".equals(exception.getCode())) {
                WorkflowRunEventEntity existing = eventRepository.queryTerminal(tenantId, userId, runId);
                if (existing != null) return existing;
            }
            throw exception;
        }
    }

    /** 以数据库为真相源持续追尾；本机通知降低延迟，周期轮询负责跨实例与丢通知恢复。 */
    public Flowable<WorkflowRunEventEntity> stream(String tenantId, String userId, String runId, long afterSequence) {
        ChatRunEntity run = requireWorkflowRun(tenantId, userId, runId);
        long safeAfter = Math.max(0, afterSequence);
        Long oldest = eventRepository.queryOldestSequence(tenantId, userId, runId);
        if (safeAfter > 0 && oldest != null && safeAfter < oldest - 1) {
            throw new AppException("WORKFLOW_EVENT_HISTORY_EXPIRED", "请求的事件历史已过保留期，请读取最终快照");
        }
        AtomicLong cursor = new AtomicLong(safeAfter);
        String streamKey = key(tenantId, runId);
        Flowable<Long> periodic = Flowable.interval(DATABASE_TAIL_INTERVAL_MILLIS,
                DATABASE_TAIL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        Flowable<Long> localWakeups = wakeups.filter(streamKey::equals).map(ignored -> 0L);
        return Flowable.merge(Flowable.just(0L), periodic, localWakeups)
                .onBackpressureLatest()
                .concatMap(ignored -> Flowable.fromIterable(eventRepository.queryAfter(
                        tenantId, userId, runId, cursor.get(), REPLAY_LIMIT)))
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

    /** 校验通用 workflow 类 chat_run 的租户、用户归属和根链路。 */
    public ChatRunEntity requireWorkflowRun(String tenantId, String userId, String runId) {
        ChatRunEntity run = chatRunRepository.query(tenantId, userId, runId);
        if (run == null || !"workflow".equals(run.getSourceType())
                || run.getTraceId() == null || run.getTraceId().isBlank()) {
            throw new AppException("WORKFLOW_RUN_NOT_FOUND", "工作流运行不存在或无权访问");
        }
        return run;
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

    private void notifyAfterCommit(String streamKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            wakeups.onNext(streamKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { wakeups.onNext(streamKey); }
        });
    }
}
