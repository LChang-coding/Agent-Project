package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.asset.service.AssetService;
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
import lombok.extern.slf4j.Slf4j;
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

    private final IChatRunRepository runRepository;
    private final SessionDomain sessionDomain;
    private final ActiveRunRegistry activeRunRegistry;
    private final ContextInvalidationService contextInvalidationService;
    private final ModelUsageService modelUsageService;
    private final AssetService assetService;

    /**
     * 创建运行控制服务；参数是运行仓储、会话服务和本机注册表；返回服务实例。
     */
    public RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                             ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService,
                             ModelUsageService modelUsageService, AssetService assetService) {
        this.runRepository = runRepository;
        this.sessionDomain = sessionDomain;
        this.activeRunRegistry = activeRunRegistry;
        this.contextInvalidationService = contextInvalidationService;
        this.modelUsageService = modelUsageService;
        this.assetService = assetService;
    }

    /**
     * 创建运行；参数是可信身份、来源和可选运行ID；返回运行实体。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity start(String tenantId, String userId, String sessionId, String sourceType,
                               String sourceId, String requestedRunId, String predecessorRunId) {
        ChatSessionEntity session = sessionDomain.lockSessionAccess(tenantId, userId, sessionId, sourceId);
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
                .status(RunStatus.RUNNING)
                .version(0)
                .baseContextRevision(revision)
                .currentContextRevision(revision)
                .predecessorRunId(predecessorRunId)
                .startedAt(now)
                .build();
        runRepository.insert(run);
        return run;
    }

    /**
     * 创建或恢复客户端已知运行；参数是可信身份、来源和运行ID；返回可执行运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity startOrResume(String tenantId, String userId, String sessionId, String sourceType,
                                       String sourceId, String requestedRunId) {
        if (blank(requestedRunId)) {
            return start(tenantId, userId, sessionId, sourceType, sourceId, null, null);
        }
        validateRequestedRunId(requestedRunId);
        ChatRunEntity existing = runRepository.query(tenantId, userId, requestedRunId);
        return existing == null
                ? start(tenantId, userId, sessionId, sourceType, sourceId, requestedRunId, null)
                : resumePrepared(tenantId, userId, sessionId, sourceType, sourceId, requestedRunId);
    }

    /**
     * 启动已由引导预建的后继运行；参数是可信身份、会话和来源；返回运行实体。
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
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 引导当前运行；参数是可信身份、运行和新指令；返回待启动后继运行。
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
        if (!blank(run.getSuccessorRunId())) {
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
                .status(RunStatus.CREATED).version(0)
                .baseContextRevision(revision).currentContextRevision(revision)
                .predecessorRunId(runId).steerInstruction(normalizedInstruction)
                .startedAt(null)
                .build();
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
        modelUsageService.cancelRunning(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                "用户引导替代");
        interruptAfterCommit(runId);
        return successor;
    }

    /**
     * 绑定用户消息；参数是运行和消息ID；返回刷新后的运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity bindUserMessage(ChatRunEntity run, String messageId) {
        if (runRepository.bindUserMessage(run.getTenantId(), run.getUserId(), run.getRunId(), messageId,
                run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "运行状态已变化，无法绑定用户消息");
        }
        return require(run.getTenantId(), run.getUserId(), run.getRunId());
    }

    /**
     * 在运行锁内写入并绑定用户消息；参数是运行身份、内容和链路ID；返回运行与消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public RunMessageBindingEntity appendUserMessage(String tenantId, String userId, String runId,
                                                      String content, String traceId) {
        return appendUserMessage(tenantId, userId, runId, content, traceId, List.of());
    }

    /**
     * 在运行锁内原子写入用户消息、绑定运行与附件；参数是运行身份、内容、链路和附件；返回运行与消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public RunMessageBindingEntity appendUserMessage(String tenantId, String userId, String runId,
                                                      String content, String traceId, List<String> attachmentIds) {
        ChatRunEntity run = lockExecutableWithSessionFirst(tenantId, userId, runId);
        ChatMessageEntity message = sessionDomain.appendUserMessage(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId, content, traceId);
        assetService.bindToMessage(run.getTenantId(), run.getUserId(), run.getSessionId(),
                message.getMessageId(), attachmentIds);
        if (runRepository.bindUserMessage(run.getTenantId(), run.getUserId(), runId, message.getMessageId(),
                run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "写入用户消息时运行状态已变化");
        }
        return RunMessageBindingEntity.builder()
                .run(require(run.getTenantId(), run.getUserId(), runId)).message(message).build();
    }

    /**
     * 在运行锁内保存助手消息并完成运行；参数是运行、内容和链路ID；返回已保存消息，已取消时返回空。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity completeWithAssistantMessage(String tenantId, String userId, String runId,
                                                          String content, String traceId) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        if (!run.getStatus().executable()) {
            return null;
        }
        ChatMessageEntity message = blank(content) ? null : sessionDomain.appendAssistantMessage(run.getTenantId(),
                run.getUserId(), run.getSessionId(), runId, content, traceId);
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(), RunStatus.COMPLETED,
                run.getVersion(), null, null, LocalDateTime.now()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "完成运行时状态已变化");
        }
        removeAfterCommit(runId);
        return message;
    }

    /**
     * 在运行锁内保存错误消息并标记失败；参数是运行、错误内容和原因；返回已保存消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity failWithAssistantMessage(String tenantId, String userId, String runId,
                                                       String content, String traceId, String reason) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        if (!run.getStatus().executable()) {
            return null;
        }
        ChatMessageEntity message = sessionDomain.appendAssistantMessage(run.getTenantId(), run.getUserId(),
                run.getSessionId(), runId, content, traceId);
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(), RunStatus.FAILED,
                run.getVersion(), reason, null, LocalDateTime.now()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "标记运行失败时状态已变化");
        }
        removeAfterCommit(runId);
        return message;
    }

    /**
     * 取消运行；参数是可信身份、运行ID和原因；返回最终运行状态。
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
        if (run.getStatus().terminal()) {
            return run;
        }
        LocalDateTime now = LocalDateTime.now();
        if (runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(),
                RunStatus.CANCEL_REQUESTED, run.getVersion(), reason, now, null) != 1) {
            return require(run.getTenantId(), run.getUserId(), runId);
        }
        int version = run.getVersion() + 1;
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
        modelUsageService.cancelRunning(run.getTenantId(), run.getUserId(), run.getSessionId(), runId,
                blank(reason) ? "用户取消" : reason);
        interruptAfterCommit(runId);
        log.info("会话运行已取消 tenantId:{} userId:{} sessionId:{} runId:{} revision:{}",
                run.getTenantId(), run.getUserId(), run.getSessionId(), runId, revision);
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 完成运行；参数是可信身份和运行ID；返回最终运行状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity complete(String tenantId, String userId, String runId) {
        ChatRunEntity run = require(tenantId, userId, runId);
        if (run.getStatus().terminal()) {
            return run;
        }
        int affected = runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(),
                RunStatus.COMPLETED, run.getVersion(), null, null, LocalDateTime.now());
        activeRunRegistry.remove(runId);
        return affected == 1 ? require(run.getTenantId(), run.getUserId(), runId)
                : require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 标记运行失败；参数是可信身份、运行ID和原因；返回最终运行状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity fail(String tenantId, String userId, String runId, String reason) {
        ChatRunEntity run = require(tenantId, userId, runId);
        if (!run.getStatus().terminal()) {
            runRepository.transition(run.getTenantId(), run.getUserId(), runId, run.getStatus(), RunStatus.FAILED,
                    run.getVersion(), reason, null, LocalDateTime.now());
        }
        activeRunRegistry.remove(runId);
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 校验运行可继续；参数是可信身份、运行ID和预期上下文版本；返回当前运行。
     */
    public ChatRunEntity requireExecutable(String tenantId, String userId, String runId, Long expectedRevision) {
        ChatRunEntity run = require(tenantId, userId, runId);
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
     * 在运行行锁下授权外部工具开始；参数是可信身份、运行和预期上下文版本；返回当前运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity authorizeToolDispatch(String tenantId, String userId, String runId, Long expectedRevision) {
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
     * 刷新运行上下文版本；参数是可信身份和运行ID；返回刷新后的运行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity refreshContextRevision(String tenantId, String userId, String runId) {
        ChatRunEntity run = require(tenantId, userId, runId);
        ChatSessionEntity session = sessionDomain.assertSessionAccess(run.getTenantId(), run.getUserId(), run.getSessionId(), null);
        long revision = session.getContextRevision() == null ? 0L : session.getContextRevision();
        if (runRepository.updateContextRevision(run.getTenantId(), run.getUserId(), runId, revision, run.getVersion()) != 1) {
            throw new AppException("RUN_CONCURRENT_MODIFICATION", "刷新运行上下文版本时状态已变化");
        }
        return require(run.getTenantId(), run.getUserId(), runId);
    }

    /**
     * 查询运行；参数是可信身份和运行ID；返回运行实体。
     */
    public ChatRunEntity require(String tenantId, String userId, String runId) {
        ChatRunEntity run = runRepository.query(tenantId, userId, runId);
        if (run == null) {
            throw new AppException("RUN_NOT_FOUND", "运行不存在或无权访问");
        }
        return run;
    }

    /**
     * 查询会话可执行运行；参数是可信身份和会话；返回活动运行列表。
     */
    public List<ChatRunEntity> queryExecutableBySession(String tenantId, String userId, String sessionId) {
        return runRepository.queryExecutableBySession(tenantId, userId, sessionId);
    }

    /**
     * 判断运行已取消；参数是可信身份和运行ID；返回是否取消。
     */
    public boolean cancelled(String tenantId, String userId, String runId) {
        ChatRunEntity run = runRepository.query(tenantId, userId, runId);
        return run != null && (run.getStatus() == RunStatus.CANCEL_REQUESTED
                || run.getStatus() == RunStatus.CANCELLING || run.getStatus() == RunStatus.CANCELLED
                || run.getStatus() == RunStatus.SUPERSEDED || run.getStatus() == RunStatus.STEER_REQUESTED);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeRequestedRunId(String requestedRunId) {
        if (blank(requestedRunId)) {
            return "run_" + UUID.randomUUID();
        }
        validateRequestedRunId(requestedRunId);
        return requestedRunId;
    }

    private void validateRequestedRunId(String requestedRunId) {
        if (requestedRunId.length() > 64 || !requestedRunId.matches("[A-Za-z0-9_-]+")) {
            throw new AppException("RUN_ID_INVALID", "运行ID格式不合法");
        }
    }

    private boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

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

    private ChatRunEntity lockExecutableWithSessionFirst(String tenantId, String userId, String runId) {
        ChatRunEntity run = lockWithSessionFirst(tenantId, userId, runId);
        if (!run.getStatus().executable()) {
            throw new AppException("RUN_NOT_EXECUTABLE", "运行已取消、被替代或结束");
        }
        return run;
    }

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

    private void removeAfterCommit(String runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activeRunRegistry.remove(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activeRunRegistry.remove(runId);
            }
        });
    }

    private void interruptAfterCommit(String runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activeRunRegistry.interrupt(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activeRunRegistry.interrupt(runId);
            }
        });
    }
}
