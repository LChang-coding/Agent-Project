package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagRunSnapshotEntity;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.service.SessionRagSettingService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.model.RunMessageBindingEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import cn.bugstack.ai.types.observability.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/**
 * 会话运行控制服务。
 * <p>负责运行创建、终结、取消和执行前状态校验。</p>
 */
@Slf4j
@Service
public class RunControlService {

    /** 持久化运行状态，并在需要时提供行锁读取。 */
    private final IChatRunRepository runRepository;
    /** 校验会话归属、保存消息和推进上下文版本。 */
    private final SessionDomain sessionDomain;
    /** 保存当前进程中可主动中断的运行句柄。 */
    private final ActiveRunRegistry activeRunRegistry;
    /** 在消息变化后使旧摘要和压缩任务失效。 */
    private final ContextInvalidationService contextInvalidationService;
    /** 将最终模型 Token 用量归集到运行记录。 */
    private final ModelUsageService modelUsageService;
    /** 校验并绑定本轮消息使用的附件。 */
    private final AssetService assetService;
    /** 缓存短时间内重复读取的无副作用运行状态。 */
    private final RunStateSnapshotCache runStateSnapshots;
    /** 在创建运行时取得并保存会话当前的 RAG 策略。 */
    private final SessionRagSettingService sessionRagSettingService;
    /** 在会话锁内复核 WAIT_ALL，防止前端检查与真实写入之间发生竞态。 */
    private final SessionOrchestrationQueryService orchestrationQueryService;

    /**
     * 创建运行控制服务。
     */
    @Autowired
    public RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                             ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService,
                             ModelUsageService modelUsageService, AssetService assetService,
                             SessionRagSettingService sessionRagSettingService,
                             SessionOrchestrationQueryService orchestrationQueryService) {
        this(runRepository, sessionDomain, activeRunRegistry, contextInvalidationService, modelUsageService,
                assetService, new RunStateSnapshotCache(), sessionRagSettingService, orchestrationQueryService);
    }

    /** 保留领域单测和旧装配入口；生产Spring装配使用带RAG策略服务的构造器。 */
    public RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                             ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService,
                             ModelUsageService modelUsageService, AssetService assetService) {
        this(runRepository, sessionDomain, activeRunRegistry, contextInvalidationService, modelUsageService,
                assetService, new RunStateSnapshotCache(), null, null);
    }

    /** 保留可注入 RAG 快照服务的测试装配入口。 */
    public RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                             ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService,
                             ModelUsageService modelUsageService, AssetService assetService,
                             SessionRagSettingService sessionRagSettingService) {
        this(runRepository, sessionDomain, activeRunRegistry, contextInvalidationService, modelUsageService,
                assetService, new RunStateSnapshotCache(), sessionRagSettingService, null);
    }

    /** 注入可控快照缓存的领域测试构造器。 */
    RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                      ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService,
                      ModelUsageService modelUsageService, AssetService assetService,
                      RunStateSnapshotCache runStateSnapshots) {
        this(runRepository, sessionDomain, activeRunRegistry, contextInvalidationService, modelUsageService,
                assetService, runStateSnapshots, null, null);
    }

    /** 汇总全部依赖的内部构造器，生产与测试最终都进入此处。 */
    RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                      ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService,
                      ModelUsageService modelUsageService, AssetService assetService,
                      RunStateSnapshotCache runStateSnapshots, SessionRagSettingService sessionRagSettingService,
                      SessionOrchestrationQueryService orchestrationQueryService) {
        this.runRepository = runRepository;
        this.sessionDomain = sessionDomain;
        this.activeRunRegistry = activeRunRegistry;
        this.contextInvalidationService = contextInvalidationService;
        this.modelUsageService = modelUsageService;
        this.assetService = assetService;
        this.runStateSnapshots = runStateSnapshots;
        this.sessionRagSettingService = sessionRagSettingService;
        this.orchestrationQueryService = orchestrationQueryService;
    }

    /**
     * 创建运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity start(String tenantId, String userId, String sessionId, String sourceType,
                               String sourceId, String requestedRunId, String predecessorRunId) {
        return createRun(tenantId, userId, sessionId, sourceType, sourceId, requestedRunId, predecessorRunId, false);
    }

    /**
     * 为子 Agent 创建运行，并继承父运行已冻结的 RAG 范围。
     * <p>子 Agent 仍然使用自己的会话、模板和工具权限；只有 RAG 开关、策略、
     * 策略版本和绑定列表来自父运行。这样既避免临时子会话默认关闭 RAG，
     * 也避免在执行中重新读取可变的会话设置。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity startSubagentWithInheritedRag(String tenantId, String userId,
                                                       String childSessionId, String childAgentId,
                                                       String parentRunId, String parentSessionId,
                                                       String parentAgentId) {
        ChatSessionEntity childSession = sessionDomain.lockSessionAccess(
                tenantId, userId, childSessionId, childAgentId);
        ChatRunEntity parent = runRepository.query(tenantId, userId, parentRunId);
        if (parent == null || !equals(parent.getSessionId(), parentSessionId)
                || !equals(parent.getSourceType(), "agent") || !equals(parent.getSourceId(), parentAgentId)) {
            throw new AppException("SUBAGENT_PARENT_SCOPE_MISMATCH", "子 Agent 任务与父运行范围不匹配");
        }
        long revision = childSession.getContextRevision() == null ? 0L : childSession.getContextRevision();
        ChatRunEntity childRun = ChatRunEntity.builder()
                .runId(normalizeRequestedRunId(null))
                .turnId("turn_" + UUID.randomUUID())
                .tenantId(childSession.getTenantId())
                .userId(childSession.getUserId())
                .sessionId(childSession.getSessionId())
                .sourceType("agent")
                .sourceId(childAgentId)
                .ragEnabled(Boolean.TRUE.equals(parent.getRagEnabled()))
                .ragMode(parent.getRagMode())
                .ragInvocationMode(parent.getRagInvocationMode())
                .ragPolicyRevision(parent.getRagPolicyRevision())
                .ragBindingIds(parent.getRagBindingIds() == null
                        ? List.of() : List.copyOf(parent.getRagBindingIds()))
                .traceId(TraceContext.ensureTraceId())
                .status(RunStatus.RUNNING)
                .version(0)
                .baseContextRevision(revision)
                .currentContextRevision(revision)
                .startedAt(LocalDateTime.now())
                .build();
        runRepository.insert(childRun);
        AiLog.info(AiLog.chat().runStarted(childRun.getTenantId(), childRun.getUserId(), childRun.getSessionId(),
                childRun.getRunId(), childRun.getSourceType(), childRun.getSourceId(), childRun.getRagEnabled()));
        invalidateSnapshotAfterCommit(childRun.getTenantId(), childRun.getUserId(), childRun.getRunId());
        return childRun;
    }

    /** 内部恢复和普通用户发送共用建 Run 逻辑，但内部恢复必须绕过 WAIT_ALL 输入闸门。 */
    private ChatRunEntity createRun(String tenantId, String userId, String sessionId, String sourceType,
                                    String sourceId, String requestedRunId, String predecessorRunId,
                                    boolean internalResume) {
        // 先锁会话再创建 run，保证创建时的上下文与 RAG 策略快照一致。
        ChatSessionEntity session = sessionDomain.lockSessionAccess(tenantId, userId, sessionId, sourceId);
        if (!internalResume && orchestrationQueryService != null) {
            orchestrationQueryService.assertAcceptsUserMessage(
                    session.getTenantId(), session.getUserId(), session.getSessionId());
        }
        SessionRagRunSnapshotEntity ragSnapshot = resolveRagSnapshot(session);
        long revision = session.getContextRevision() == null ? 0L : session.getContextRevision();
        LocalDateTime now = LocalDateTime.now();
        ChatRunEntity run = ChatRunEntity.builder()
                .runId(normalizeRequestedRunId(requestedRunId))
                .turnId("turn_" + UUID.randomUUID())
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .sourceType(sourceType)
                .sourceId(sourceId)
                .ragEnabled(ragSnapshot.enabled()).ragMode(ragSnapshot.mode().name())
                .ragInvocationMode(ragSnapshot.invocationMode().name())
                .ragPolicyRevision(ragSnapshot.revision()).ragBindingIds(ragSnapshot.bindingIds())
                .traceId(TraceContext.ensureTraceId())
                .status(RunStatus.RUNNING)
                .version(0)
                .baseContextRevision(revision)
                .currentContextRevision(revision)
                .predecessorRunId(predecessorRunId)
                .startedAt(now)
                .build();
        runRepository.insert(run) ;
        AiLog.info(AiLog.chat().runStarted(run.getTenantId(), run.getUserId(), run.getSessionId(),
                run.getRunId(), run.getSourceType(), run.getSourceId(), run.getRagEnabled()));
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), run.getRunId());
        return run;
    }

    /**
     * 创建或恢复客户端已知运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity startOrResume(String tenantId, String userId, String sessionId, String sourceType,
                                       String sourceId, String requestedRunId) {
        if (blank(requestedRunId)) {
            return start(tenantId, userId, sessionId, sourceType, sourceId, null, null);
        }
        validateRequestedRunId(requestedRunId);
        ChatRunEntity existing = runRepository.query(tenantId, userId, requestedRunId);
        // 客户端重试只能恢复引导预建的 CREATED 运行，不能复用任意旧 run。
        return existing == null
                ? start(tenantId, userId, sessionId, sourceType, sourceId, requestedRunId, null)
                : resumePrepared(tenantId, userId, sessionId, sourceType, sourceId, requestedRunId);
    }

    /**
     * 为平台内部恢复调用复用稳定运行。恢复 Worker 可能在最终消息落库后重投，
     * 因此已存在的 RUNNING 或终态运行都必须原样返回，不能再创建第二条运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity startOrReuseInternal(String tenantId, String userId, String sessionId,
                                              String sourceType, String sourceId, String requestedRunId) {
        validateRequestedRunId(requestedRunId);
        ChatRunEntity existing = runRepository.query(tenantId, userId, requestedRunId);
        if (existing == null) {
            return createRun(tenantId, userId, sessionId, sourceType, sourceId, requestedRunId, null, true);
        }
        if (!equals(existing.getSessionId(), sessionId) || !equals(existing.getSourceType(), sourceType)
                || !equals(existing.getSourceId(), sourceId)) {
            throw new AppException("RUN_SCOPE_MISMATCH", "内部恢复运行与当前会话或执行源不匹配");
        }
        if (existing.getStatus() != RunStatus.FAILED) return existing;

        // 汇总模型失败后保留同一稳定 runId，但必须把 FAILED 重新打开为 RUNNING 才会再次调用模型。
        sessionDomain.lockSessionAccess(existing.getTenantId(), existing.getUserId(), existing.getSessionId(), null);
        ChatRunEntity failed = runRepository.lock(existing.getTenantId(), existing.getUserId(), requestedRunId);
        if (failed == null || !equals(failed.getSessionId(), sessionId)
                || !equals(failed.getSourceType(), sourceType) || !equals(failed.getSourceId(), sourceId)) {
            throw new AppException("RUN_SCOPE_MISMATCH", "内部恢复运行与当前会话或执行源不匹配");
        }
        if (failed.getStatus() != RunStatus.FAILED) return failed;
        if (runRepository.transition(failed.getTenantId(), failed.getUserId(), requestedRunId,
                RunStatus.FAILED, RunStatus.RUNNING, failed.getVersion(), null, null, null) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "内部恢复运行重试状态已变化");
        }
        invalidateSnapshotAfterCommit(failed.getTenantId(), failed.getUserId(), requestedRunId);
        return require(failed.getTenantId(), failed.getUserId(), requestedRunId);
    }

    /**
     * 启动已由引导预建的后继运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity resumePrepared(String tenantId, String userId, String sessionId, String sourceType,
                                        String sourceId, String runId) {
        ChatRunEntity run = runRepository.lock(tenantId, userId, runId);
        if (run == null) {
            throw new AppException("RUN_NOT_FOUND", "待启动的后继运行不存在");
        }
        if (!equals(run.getSessionId(), sessionId) || !equals(run.getSourceType(), sourceType)
                || !equals(run.getSourceId(), sourceId)) {
            throw new AppException("RUN_SCOPE_MISMATCH", "后继运行与当前会话或执行源不匹配");
        }
        if (run.getStatus() != RunStatus.CREATED || blank(run.getPredecessorRunId())) {
            throw new AppException("RUN_NOT_PREPARED", "运行不是可启动的引导后继状态");
        }
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, RunStatus.CREATED,
                RunStatus.RUNNING, run.getVersion(), null, null, null) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "后继运行已被启动或状态已变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 引导当前运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity steer(String tenantId, String userId, String runId, String instruction) {
        String normalizedInstruction = normalizeInstruction(instruction);
        ChatRunEntity scope = runRepository.query(tenantId, userId, runId);
        if (scope == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        sessionDomain.lockSessionAccess(scope.getTenantId(), scope.getUserId(), scope.getSessionId(), null);
        ChatRunEntity run = runRepository.lock(scope.getTenantId(), scope.getUserId(), runId);
        if (run == null || !equals(scope.getSessionId(), run.getSessionId())) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        if (orchestrationQueryService != null) {
            orchestrationQueryService.assertAcceptsRunMutation(run.getTenantId(), run.getRunId());
        }
        if (!blank(run.getSuccessorRunId())) {
            // 相同指令的重试返回既有后继；不同指令必须显式冲突，不能分叉同一旧运行。
            ChatRunEntity existing = require(run.getTenantId(), run.getUserId(), run.getSuccessorRunId());
            if (!normalizedInstruction.equals(existing.getSteerInstruction())) {
                throw new AppException("RUN_STEER_CONFLICT", "当前运行已存在不同的引导后继");
            }
            return existing;
        }
        if (!run.getStatus().executable()) {
            throw new AppException("RUN_NOT_EXECUTABLE", "仅执行中的运行可以接收引导");
        }
        LocalDateTime now = LocalDateTime.now();
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(),
                RunStatus.STEER_REQUESTED, run.getVersion(), "用户发起引导", now, null) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "引导过程中运行状态已变化");
        }
        int version = run.getVersion() + 1;
        // 旧运行产生的全部消息先失效，再让压缩与引用派生数据同步回滚。
        List<ChatMessageEntity> runMessages = sessionDomain.queryRunMessages(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId);
        sessionDomain.invalidateRunMessages(run.getTenantId(), run.getUserId(), run.getSessionId(), runId, "用户引导替代");
        contextInvalidationService.invalidateRun(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                runMessages, "用户引导替代");
        long revision = sessionDomain.incrementContextRevision(run.getTenantId(), run.getUserId(), run.getSessionId());
        if (runRepository.updateContextRevision(run.getTenantId(), run.getUserId(), runId, revision, version) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "引导过程中上下文版本已变化");
        }
        version++;
        ChatRunEntity successor = ChatRunEntity.builder()
                .runId("run_" + UUID.randomUUID())
                .turnId("turn_" + UUID.randomUUID())
                .tenantId(run.getTenantId()).userId(run.getUserId()).sessionId(run.getSessionId())
                .sourceType(run.getSourceType()).sourceId(run.getSourceId())
                .ragEnabled(Boolean.TRUE.equals(run.getRagEnabled())).ragMode(run.getRagMode())
                .ragInvocationMode(run.getRagInvocationMode())
                .ragPolicyRevision(run.getRagPolicyRevision()).ragBindingIds(run.getRagBindingIds())
                .traceId(run.getTraceId())
                .status(RunStatus.CREATED).version(0)
                .baseContextRevision(revision).currentContextRevision(revision)
                .predecessorRunId(runId).steerInstruction(normalizedInstruction)
                .startedAt(null)
                .build();
        // 后继继承本轮固化的 RAG 策略与 traceId，避免引导中途配置漂移或链路断裂。
        runRepository.insert(successor);
        if (runRepository.bindSuccessor(run.getTenantId(), run.getUserId(), runId, successor.getRunId(),
                normalizedInstruction, version) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "引导过程中后继关系建立失败");
        }
        version++;
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, RunStatus.STEER_REQUESTED,
                RunStatus.SUPERSEDED, version, "已由引导后继运行替代", now, now) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "引导过程中旧运行终结失败");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        invalidateSnapshotAfterCommit(successor.getTenantId(), successor.getUserId(), successor.getRunId());
        modelUsageService.cancelRunning(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                "用户引导替代");
        interruptAfterCommit(runId);
        return successor;
    }

    /** 将会话 RAG 配置冻结为本轮不可变快照；旧单测装配安全降级为空绑定。 */
    private SessionRagRunSnapshotEntity resolveRagSnapshot(ChatSessionEntity session) {
        if (sessionRagSettingService != null) {
            return sessionRagSettingService.resolveRunSnapshot(session);
        }
        SessionRagMode mode = SessionRagMode.resolve(session.getRagMode(), session.getRagEnabled());
        if (mode == SessionRagMode.OFF) {
            return new SessionRagRunSnapshotEntity(mode,
                    cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode.resolve(session.getRagInvocationMode()),
                    session.getRagRevision() == null ? 0L : session.getRagRevision(), List.of());
        }
        // 仅供不装配RAG仓储的旧领域单测；生产路径绝不会生成空绑定快照。
        return new SessionRagRunSnapshotEntity(mode,
                cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode.resolve(session.getRagInvocationMode()),
                session.getRagRevision() == null ? 0L : session.getRagRevision(), List.of());
    }

    /**
     * 绑定用户消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity bindUserMessage(ChatRunEntity run, String messageId) {
        if (runRepository.bindUserMessage(run.getTenantId(), run.getUserId(), run.getRunId(), messageId,
                run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "运行状态已变化，无法绑定用户消息");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), run.getRunId());
        return require(run.getTenantId(), run.getUserId(), run.getRunId());
    }

    /**
     * 在运行锁内写入并绑定用户消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public RunMessageBindingEntity appendUserMessage(String tenantId, String userId, String runId,
                                                      String content, String traceId) {
        return appendUserMessage(tenantId, userId, runId, content, traceId, List.of());
    }

    /**
     * 在运行锁内原子写入用户消息、绑定运行与附件。
     */
    @Transactional(rollbackFor = Exception.class)
    public RunMessageBindingEntity appendUserMessage(String tenantId, String userId, String runId,
                                                      String content, String traceId, List<String> attachmentIds) {
        // 统一锁顺序为会话后运行，避免取消、删除和消息写入之间形成死锁。
        ChatRunEntity run = lockExecutableWithSessionFirst(tenantId, userId, runId);
        ChatMessageEntity message = sessionDomain.appendUserMessage(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId, content, traceId);
        assetService.bindToMessage(run.getTenantId(), run.getUserId(), run.getSessionId(),
                message.getMessageId(), attachmentIds);
        if (runRepository.bindUserMessage(run.getTenantId(), run.getUserId(), runId, message.getMessageId(),
                run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "写入用户消息时运行状态已变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        return RunMessageBindingEntity.builder()
                .run(require(run.getTenantId(), run.getUserId(), runId)).message(message).build();
    }

    /** 在运行锁内写入并绑定平台内部输入，不伪装成用户消息。 */
    @Transactional(rollbackFor = Exception.class)
    public RunMessageBindingEntity appendPlatformMessage(String tenantId, String userId, String runId,
                                                          String content, String traceId) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        if (!blank(run.getUserMessageId())) {
            ChatMessageEntity existing = sessionDomain.queryValidMessage(run.getTenantId(), run.getUserId(),
                    run.getSessionId(), run.getUserMessageId());
            if (existing == null || !SessionDomain.ROLE_PLATFORM.equals(existing.getRole())) {
                throw new AppException("RUN_PLATFORM_MESSAGE_CONFLICT", "内部恢复运行已绑定的输入消息不合法");
            }
            return RunMessageBindingEntity.builder().run(run).message(existing).build();
        }
        if (!run.getStatus().executable()) {
            throw new AppException("RUN_NOT_EXECUTABLE", "运行已取消、被替代或结束");
        }
        ChatMessageEntity message = sessionDomain.appendPlatformMessage(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId, content, traceId);
        if (runRepository.bindUserMessage(run.getTenantId(), run.getUserId(), runId, message.getMessageId(),
                run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "写入平台消息时运行状态已变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        return RunMessageBindingEntity.builder()
                .run(require(run.getTenantId(), run.getUserId(), runId)).message(message).build();
    }

    /**
     * 在运行锁内保存助手消息并完成运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity completeWithAssistantMessage(String tenantId, String userId, String runId,
                                                          String content, String traceId) {
        return completeWithAssistantMessage(tenantId, userId, runId, content, traceId, null);
    }

    /** 在运行锁内原子保存带安全元数据的助手消息并完成运行。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity completeWithAssistantMessage(String tenantId, String userId, String runId,
                                                           String content, String traceId, String metadata) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        assertSummaryResumeLease(run.getTenantId());
        if (!run.getStatus().executable()) {
            // 取消或引导已抢先落库时，不再保存迟到的助手消息。
            return null;
        }
        ChatMessageEntity message = blank(content) ? null : sessionDomain.appendAssistantMessage(run.getTenantId(),
                run.getUserId(), run.getSessionId(), runId, content, traceId, metadata);
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(), RunStatus.COMPLETED,
                run.getVersion(), null, null, LocalDateTime.now()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "完成运行时状态已变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        removeAfterCommit(runId);
        AiLog.info(AiLog.chat().runCompleted(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                run.getRagEnabled(), content == null ? 0 : content.length(), elapsed(run))
                .field(AiLogFields.TRACE_ID, traceId));
        return message;
    }

    /**
     * 在运行锁内保存错误消息并标记失败。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity failWithAssistantMessage(String tenantId, String userId, String runId,
                                                       String content, String traceId, String reason) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        if (!run.getStatus().executable()) {
            // 终态运行不接收迟到错误消息，避免被取消消息重新污染上下文。
            return null;
        }
        ChatMessageEntity message = sessionDomain.appendAssistantMessage(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId, content, traceId);
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(), RunStatus.FAILED,
                run.getVersion(), reason, null, LocalDateTime.now()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "标记运行失败时状态已变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        removeAfterCommit(runId);
        AiLog.error(AiLog.chat().runFailed(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                run.getRagEnabled(), elapsed(run), new IllegalStateException(reason))
                .field(AiLogFields.TRACE_ID, traceId));
        return message;
    }

    /**
     * 取消运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity cancel(String tenantId, String userId, String runId, String reason) {
        ChatRunEntity scope = runRepository.query(tenantId, userId, runId);
        if (scope == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        sessionDomain.lockSessionAccess(scope.getTenantId(), scope.getUserId(), scope.getSessionId(), null);
        ChatRunEntity run = runRepository.lock(scope.getTenantId(), scope.getUserId(), runId);
        if (run == null || !equals(scope.getSessionId(), run.getSessionId())) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        if (orchestrationQueryService != null) {
            orchestrationQueryService.assertAcceptsRunMutation(run.getTenantId(), run.getRunId());
        }
        if (run.getStatus().terminal()) {
            // 取消接口幂等：终态不再重复失效消息或推进上下文版本。
            return run;
        }
        LocalDateTime now = LocalDateTime.now();
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(),
                RunStatus.CANCEL_REQUESTED, run.getVersion(), reason, now, null) != 1) {
            return require(run.getTenantId(), run.getUserId(), runId);
        }
        int version = run.getVersion() + 1;
        // 取消必须同时撤销消息、压缩派生状态和模型用量中的运行中记录。
        List<ChatMessageEntity> runMessages = sessionDomain.queryRunMessages(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId);
        sessionDomain.invalidateRunMessages(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                blank(reason) ? "用户取消" : reason);
        contextInvalidationService.invalidateRun(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                runMessages, blank(reason) ? "用户取消" : reason);
        long revision = sessionDomain.incrementContextRevision(run.getTenantId(), run.getUserId(), run.getSessionId());
        if (runRepository.updateContextRevision(run.getTenantId(), run.getUserId(), runId, revision, version) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "取消过程中上下文版本发生变化");
        }
        version++;
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, RunStatus.CANCEL_REQUESTED,
                RunStatus.CANCELLED, version, reason, now, now) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "取消过程中运行状态发生变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        modelUsageService.cancelRunning(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                blank(reason) ? "用户取消" : reason);
        interruptAfterCommit(runId);
        AiLog.info(AiLog.chat().runCancelled(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                run.getRagEnabled(), blank(reason) ? "用户取消" : reason, elapsed(run))
                // 取消 HTTP 有独立操作 Trace；运行终态必须归入被取消 Run 的根 Trace。
                .field(AiLogFields.TRACE_ID, run.getTraceId()));
        log.info("会话运行已取消 tenantId:{} userId:{} sessionId:{} runId:{} revision:{}",
                run.getTenantId(), run.getUserId(), run.getSessionId(), runId, revision);
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 完成运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity complete(String tenantId, String userId, String runId) {
        ChatRunEntity run = require(tenantId, userId, runId);
        if (run.getStatus().terminal()) {
            return run;
        }
        int affected = runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(),
                RunStatus.COMPLETED, run.getVersion(), null, null, LocalDateTime.now());
        if (affected == 1) {
            invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        }
        removeAfterCommit(runId);
        return affected == 1 ? require(run.getTenantId(), run.getUserId(), runId)
                : require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 标记运行失败。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity fail(String tenantId, String userId, String runId, String reason) {
        ChatRunEntity run = AgentOrchestrationContextHolder.isSummaryOnly()
                ? lockWithSessionFirst(tenantId, userId, runId)
                : require(tenantId, userId, runId);
        assertSummaryResumeLease(run.getTenantId());
        if (!run.getStatus().terminal()) {
            runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(), RunStatus.FAILED,
                    run.getVersion(), reason, null, LocalDateTime.now());
            invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        }
        removeAfterCommit(runId);
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 委派前按统一顺序锁定父会话与父运行。调用方随后在同一事务中写 WAIT_ALL 账本，
     * 从而与发送、取消、引导和删除形成互斥，而不是依赖事前查询。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity lockParentForDelegation(String tenantId, String userId, String parentSessionId,
                                                 String parentRunId, String parentAgentId) {
        ChatSessionEntity session = sessionDomain.lockSessionAccess(
                tenantId, userId, parentSessionId, parentAgentId);
        ChatRunEntity run = runRepository.lock(session.getTenantId(), session.getUserId(), parentRunId);
        if (run == null || !equals(run.getSessionId(), session.getSessionId())
                || !equals(run.getSourceId(), parentAgentId)) {
            throw new AppException("SUBAGENT_PARENT_SCOPE_INVALID", "子 Agent 委派的父运行与当前会话不匹配");
        }
        if (!run.getStatus().executable()) {
            throw new AppException("SUBAGENT_PARENT_RUN_NOT_EXECUTABLE", "父 Agent 运行已结束，不能继续委派");
        }
        return run;
    }

    /** 计算运行开始至当前的非负耗时。 */
    private long elapsed(ChatRunEntity run) {
        if (run == null || run.getStartedAt() == null) {
            return 0L;
        }
        return Math.max(0L, java.time.Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis());
    }

    /**
     * 校验运行可继续。
     */
    public ChatRunEntity requireExecutable(String tenantId, String userId, String runId, Long expectedRevision) {
        RunStateSnapshotCache.Snapshot snapshot = readSnapshot(tenantId, userId, runId);
        if (snapshot.run() == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        if (!snapshot.status().executable()) {
            throw new AppException("RUN_NOT_EXECUTABLE", "运行已取消、被替代或结束");
        }
        if (expectedRevision != null && snapshot.contextRevision() != null
                && !expectedRevision.equals(snapshot.contextRevision())) {
            throw new AppException("RUN_CONTEXT_STALE", "运行上下文版本已变化，需要重新推理");
        }
        return runStateSnapshots.materialize(snapshot);
    }

    /**
     * 在运行行锁下授权外部工具开始。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity authorizeToolDispatch(String tenantId, String userId, String runId, Long expectedRevision) {
        // 外部副作用前必须直接锁数据库，不能依赖最多两百毫秒的只读快照。
        ChatRunEntity run = runRepository.lock(tenantId, userId, runId);
        if (run == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        if (!run.getStatus().executable()) {
            throw new AppException("RUN_NOT_EXECUTABLE", "运行已取消、被替代或结束");
        }
        if (expectedRevision != null && run.getCurrentContextRevision() != null
                && !expectedRevision.equals(run.getCurrentContextRevision())) {
            throw new AppException("RUN_CONTEXT_STALE", "运行上下文版本已变化，需要重新推理");
        }
        return run;
    }

    /**
     * 刷新运行上下文版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity refreshContextRevision(String tenantId, String userId, String runId) {
        ChatRunEntity run = require(tenantId, userId, runId);
        ChatSessionEntity session = sessionDomain.assertSessionAccess(run.getTenantId(), run.getUserId(), run.getSessionId(), null);
        long revision = session.getContextRevision() == null ? 0L : session.getContextRevision();
        if (runRepository.updateContextRevision(run.getTenantId(), run.getUserId(), runId, revision, run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "刷新运行上下文版本时状态已变化");
        }
        invalidateSnapshotAfterCommit(run.getTenantId(), run.getUserId(), runId);
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 查询运行。
     */
    public ChatRunEntity require(String tenantId, String userId, String runId) {
        ChatRunEntity run = runRepository.query(tenantId, userId, runId);
        if (run == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        return run;
    }

    /**
     * 查询会话可执行运行。
     */
    public List<ChatRunEntity> queryExecutableBySession(String tenantId, String userId, String sessionId) {
        return runRepository.queryExecutableBySession(tenantId, userId, sessionId);
    }

    /**
     * 判断运行已取消。
     */
    public boolean cancelled(String tenantId, String userId, String runId) {
        RunStatus status = readSnapshot(tenantId, userId, runId).status();
        return status == RunStatus.CANCEL_REQUESTED || status == RunStatus.CANCELLING
                || status == RunStatus.CANCELLED || status == RunStatus.SUPERSEDED
                || status == RunStatus.STEER_REQUESTED;
    }

    /** 从极短 TTL 快照读取无副作用状态检查所需字段。 */
    private RunStateSnapshotCache.Snapshot readSnapshot(String tenantId, String userId, String runId) {
        return runStateSnapshots.get(tenantId, userId, runId,
                () -> runRepository.query(tenantId, userId, runId));
    }

    /** 判断可选字符串是否缺失。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 使用客户端合法幂等键，未提供时由服务端生成。 */
    private String normalizeRequestedRunId(String requestedRunId) {
        if (blank(requestedRunId)) {
            return "run_" + UUID.randomUUID();
        }
        validateRequestedRunId(requestedRunId);
        return requestedRunId;
    }

    /** 限制运行标识字符集和长度，避免持久化与日志注入。 */
    private void validateRequestedRunId(String requestedRunId) {
        if (requestedRunId.length() > 64 || !requestedRunId.matches("[A-Za-z0-9_-]+")) {
            throw new AppException("RUN_ID_INVALID", "运行ID格式不合法");
        }
    }

    /** 对可空作用域字段执行安全相等比较。 */
    private boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** 清理并限制引导指令，防止空引导和无界提示词。 */
    private String normalizeInstruction(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new AppException("RUN_STEER_INSTRUCTION_EMPTY", "引导指令不能为空");
        }
        String normalized = instruction.trim();
        if (normalized.length() > 4_000) {
            throw new AppException("RUN_STEER_INSTRUCTION_TOO_LONG", "引导指令不能超过 4000 个字符");
        }
        return normalized;
    }

    /** 按统一锁序取得运行，并要求其仍可产生业务动作。 */
    private ChatRunEntity lockExecutableWithSessionFirst(String tenantId, String userId, String runId) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        if (!run.getStatus().executable()) {
            throw new AppException("RUN_NOT_EXECUTABLE", "运行已取消、被替代或结束");
        }
        return run;
    }

    /** 先锁所属会话再锁运行，并在两次读取间复核归属未漂移。 */
    private ChatRunEntity lockWithSessionFirst(String tenantId, String userId, String runId) {
        ChatRunEntity scope = runRepository.query(tenantId, userId, runId);
        if (scope == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        sessionDomain.lockSessionAccess(scope.getTenantId(), scope.getUserId(), scope.getSessionId(), null);
        ChatRunEntity run = runRepository.lock(scope.getTenantId(), scope.getUserId(), runId);
        if (run == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        if (!equals(scope.getSessionId(), run.getSessionId())) {
            throw new AppException("RUN_SCOPE_MISMATCH", "运行与会话归属不一致");
        }
        return run;
    }

    /** summary-only 恢复必须在消息事务内持有 resume 行锁，失租 Worker 不得提交终态。 */
    private void assertSummaryResumeLease(String tenantId) {
        if (AgentOrchestrationContextHolder.isSummaryOnly()) {
            if (orchestrationQueryService == null) {
                throw new AppException("PARENT_RESUME_LEASE_UNAVAILABLE", "主 Agent 恢复租约校验服务不可用");
            }
            orchestrationQueryService.assertOwnsResumeLease(tenantId);
        }
    }

    /** 事务提交后再失效快照，避免回滚事务把缓存中的旧事实提前删除。 */
    private void invalidateSnapshotAfterCommit(String tenantId, String userId, String runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runStateSnapshots.invalidate(tenantId, userId, runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            /** 运行创建事务提交后再执行异步启动动作。 */
            public void afterCommit() {
                runStateSnapshots.invalidate(tenantId, userId, runId);
            }
        });
    }

    /** 终态提交后移除本机中断句柄。 */
    private void removeAfterCommit(String runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activeRunRegistry.remove(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            /** 状态推进提交后再发布对应运行事件。 */
            public void afterCommit() {
                activeRunRegistry.remove(runId);
            }
        });
    }

    /** 取消或引导提交后才中断本机流，确保观察者能读到持久化终态。 */
    private void interruptAfterCommit(String runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activeRunRegistry.interrupt(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            /** 取消事务提交后再触发取消后的清理和通知。 */
            public void afterCommit() {
                activeRunRegistry.interrupt(runId);
            }
        });
    }
}
