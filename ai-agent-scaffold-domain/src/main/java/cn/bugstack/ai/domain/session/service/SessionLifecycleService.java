package cn.bugstack.ai.domain.session.service;

import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.share.adapter.ISessionShareRepository;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话生命周期服务。
 * <p>负责删除会话前收敛运行、分享和上下文派生状态。</p>
 */
@Service
public class SessionLifecycleService {

    /** 会话删除触发运行取消和上下文失效时使用的统一原因。 */
    private static final String DELETE_REASON = "会话已删除";
    /** 锁定会话、推进上下文版本并执行软删除。 */
    private final SessionDomain sessionDomain;
    /** 查询并取消会话中仍可执行的运行。 */
    private final RunControlService runControlService;
    /** 使会话派生的摘要和压缩任务失效。 */
    private final ContextInvalidationService contextInvalidationService;
    /** 撤销该会话已经创建的分享授权。 */
    private final ISessionShareRepository shareRepository;
    /** WAIT_ALL 活跃期间阻止删除父会话。 */
    private final SessionOrchestrationQueryService orchestrationQueryService;

    /**
     * 创建会话生命周期服务。
     */
    public SessionLifecycleService(SessionDomain sessionDomain, RunControlService runControlService,
                                   ContextInvalidationService contextInvalidationService,
                                   ISessionShareRepository shareRepository,
                                   SessionOrchestrationQueryService orchestrationQueryService) {
        this.sessionDomain = sessionDomain;
        this.runControlService = runControlService;
        this.contextInvalidationService = contextInvalidationService;
        this.shareRepository = shareRepository;
        this.orchestrationQueryService = orchestrationQueryService;
    }

    /**
     * 软删除会话。
     */
    @Transactional(rollbackFor = Exception.class)
    public long delete(String tenantId, String userId, String sessionId) {
        orchestrationQueryService.assertAcceptsSessionMutation(tenantId, userId, sessionId);
        // 固定锁序先锁会话，阻止删除期间创建新运行或追加消息。
        ChatSessionEntity session = sessionDomain.lockSessionAccess(tenantId, userId, sessionId, null);
        // 必须在取得会话锁后再查一次，封住事前检查与加锁之间新建 WAIT_ALL 的窗口。
        orchestrationQueryService.assertAcceptsSessionMutation(
                session.getTenantId(), session.getUserId(), session.getSessionId());
        List<ChatRunEntity> runs = runControlService.queryExecutableBySession(session.getTenantId(),
                session.getUserId(), session.getSessionId());
        for (ChatRunEntity run : runs) {
            // 每个可执行 run 必须先完成取消闭环，不能只软删会话掩盖后台副作用。
            ChatRunEntity cancelled = runControlService.cancel(session.getTenantId(), session.getUserId(),
                    run.getRunId(), DELETE_REASON);
            if (cancelled.getStatus() != RunStatus.CANCELLED && cancelled.getStatus() != RunStatus.SUPERSEDED) {
                throw new AppException("SESSION_ACTIVE_RUN_CONFLICT", "会话仍有无法取消的运行");
            }
        }
        long revision = sessionDomain.incrementContextRevision(session.getTenantId(), session.getUserId(),
                session.getSessionId());
        // 删除前撤销上下文派生状态和分享授权，保证旧链接及摘要不可继续使用。
        contextInvalidationService.invalidateSession(session.getTenantId(), session.getUserId(),
                session.getSessionId(), DELETE_REASON);
        shareRepository.revokeBySession(session.getTenantId(), session.getUserId(), session.getSessionId());
        if (sessionDomain.softDelete(session.getTenantId(), session.getUserId(), session.getSessionId()) != 1) {
            throw new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo());
        }
        return revision;
    }
}
