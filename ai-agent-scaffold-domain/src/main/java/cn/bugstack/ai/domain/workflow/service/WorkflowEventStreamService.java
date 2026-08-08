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

    /** 当前工作流事件载荷协议版本。 */
    public static final String SCHEMA_VERSION = "workflow-event-v1";
    /** 单次数据库续读允许返回的最大事件数。 */
    private static final int REPLAY_LIMIT = 1000;
    /** 没有本机通知时主动检查数据库的时间间隔。 */
    private static final long DATABASE_TAIL_INTERVAL_MILLIS = 1000L;

    /** 分配事件序号、追加事件并按序号续读。 */
    private final IWorkflowEventRepository eventRepository;
    /** 查询智能工作流扩展运行状态。 */
    private final IIntelligentWorkflowRunRepository runRepository;
    /** 校验通用工作流运行的租户、用户和跟踪标识。 */
    private final IChatRunRepository chatRunRepository;
    /** 只发送运行 ID 唤醒本进程订阅者，事件正文始终从持久化仓储续读。 */
    private final FlowableProcessor<String> wakeups = PublishProcessor.<String>create().toSerialized();

    /**
     * 创建工作流事件服务。
     *
     * @param eventRepository 持久化和续读事件的仓储
     * @param runRepository 查询智能工作流运行的仓储
     * @param chatRunRepository 校验通用工作流运行归属的仓储
     */
    public WorkflowEventStreamService(IWorkflowEventRepository eventRepository,
                                      IIntelligentWorkflowRunRepository runRepository,
                                      IChatRunRepository chatRunRepository) {
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.chatRunRepository = chatRunRepository;
    }

    /**
     * 持久化一个工作流事件，并在事务提交后通知本实例订阅者。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 事件所属运行
     * @param traceId 与根运行一致的跟踪标识
     * @param eventType 事件业务类型
     * @param nodeExecutionId 关联的逻辑节点执行；运行级事件可以为空
     * @param nodeId 关联的工作流节点；运行级事件可以为空
     * @param payloadJson 按事件类型定义的 JSON 载荷；为空时保存空对象
     * @return 包含运行内递增序号的持久化事件
     */
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

    /**
     * 从指定序号之后持续读取工作流事件，直到交付终态事件。
     *
     * <p>数据库保存完整事件顺序；本机通知用于降低延迟，周期查询用于读取其他实例写入或通知期间遗漏的事件。</p>
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待订阅的工作流运行
     * @param afterSequence 客户端已处理的最后序号；零表示从最早可用事件开始
     * @return 按运行内序号交付且在终态事件后结束的数据流
     */
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

    /**
     * 查询当前用户有权读取的智能工作流运行。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待查询的运行标识
     * @return 与租户、用户和运行标识同时匹配的运行实体
     */
    public IntelligentWorkflowRunEntity requireRun(String tenantId, String userId, String runId) {
        IntelligentWorkflowRunEntity run = runRepository.query(tenantId, userId, runId);
        if (run == null) {
            throw new AppException("WORKFLOW_RUN_NOT_FOUND", "智能工作流运行不存在或无权访问");
        }
        return run;
    }

    /**
     * 校验通用运行属于当前用户、类型为 workflow 且包含有效跟踪标识。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待校验的运行标识
     * @return 可用于事件订阅的通用工作流运行
     */
    public ChatRunEntity requireWorkflowRun(String tenantId, String userId, String runId) {
        ChatRunEntity run = chatRunRepository.query(tenantId, userId, runId);
        if (run == null || !"workflow".equals(run.getSourceType())
                || run.getTraceId() == null || run.getTraceId().isBlank()) {
            throw new AppException("WORKFLOW_RUN_NOT_FOUND", "工作流运行不存在或无权访问");
        }
        return run;
    }

    /** 组合本机唤醒通知的租户与运行标识，分隔符避免字符串拼接冲突。 */
    private String key(String tenantId, String runId) {
        return tenantId + "\u0000" + runId;
    }

    /** 判断事件是否表示工作流已经完成、失败或取消。 */
    private boolean terminal(String eventType) {
        return "WORKFLOW_COMPLETED".equals(eventType)
                || "WORKFLOW_FAILED".equals(eventType)
                || "WORKFLOW_CANCELLED".equals(eventType);
    }

    /** 校验事件身份和类型字段，阻止无法归属或重放的事件入库。 */
    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new AppException("WORKFLOW_EVENT_INVALID", message);
    }

    /** 仅在事务成功提交后唤醒本实例订阅者，回滚事务不发送通知。 */
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
