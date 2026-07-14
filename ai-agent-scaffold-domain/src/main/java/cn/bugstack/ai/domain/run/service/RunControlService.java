package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
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

    /**
     * 创建运行控制服务；参数是运行仓储、会话服务和本机注册表；返回服务实例。
     */
    public RunControlService(IChatRunRepository runRepository, SessionDomain sessionDomain,
                             ActiveRunRegistry activeRunRegistry, ContextInvalidationService contextInvalidationService) {
        this.runRepository = runRepository;
        this.sessionDomain = sessionDomain;
        this.activeRunRegistry = activeRunRegistry;
        this.contextInvalidationService = contextInvalidationService;
    }

    /**
     * 创建运行；参数是可信身份、来源和可选运行ID；返回运行实体。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity start(String tenantId, String userId, String sessionId, String sourceType,
                               String sourceId, String requestedRunId, String predecessorRunId) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, sourceId);
        long revision = session.getContextRevision() == null ? 0L : session.getContextRevision();
        LocalDateTime now = LocalDateTime.now();
        ChatRunEntity run = ChatRunEntity.builder()
                .runId(blank(requestedRunId) ? "run_" + UUID.randomUUID() : requestedRunId)
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
     * 取消运行；参数是可信身份、运行ID和原因；返回最终运行状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatRunEntity cancel(String tenantId, String userId, String runId, String reason) {
        ChatRunEntity run = runRepository.lock(tenantId, userId, runId);
        if (run == null) {
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
