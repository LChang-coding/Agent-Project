package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 将主 Agent 自身运行终态与 WAIT_ALL 草稿屏障收在同一个数据库事务中。 */
@Service
public class ParentWaitAllFinalizationService {
    private final IParentResumeRepository parentResumeRepository;
    private final RunControlService runControlService;

    public ParentWaitAllFinalizationService(IParentResumeRepository parentResumeRepository,
                                            RunControlService runControlService) {
        this.parentResumeRepository = parentResumeRepository;
        this.runControlService = runControlService;
    }

    /** 普通运行返回 false；已委派子任务时隐藏正文、完成原运行并打开父侧屏障。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean completeAsDraftIfWaiting(String tenantId, String userId, String runId, String parentDraft) {
        if (!parentResumeRepository.isAwaitingSummary(tenantId, runId)) return false;
        ChatRunEntity finalized = runControlService.complete(tenantId, userId, runId);
        if (finalized.getStatus() != RunStatus.COMPLETED) {
            throw new AppException("PARENT_RUN_FINALIZATION_CONFLICT", "父 Agent 运行已被其他终态抢占");
        }
        markParentReady(tenantId, runId, parentDraft);
        return true;
    }

    /** 主 Agent 自身执行失败也要形成隐藏草稿，使已启动的子任务最终仍能统一汇总。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean failAsDraftIfWaiting(String tenantId, String userId, String runId,
                                        String parentDraft, String failureReason) {
        if (!parentResumeRepository.isAwaitingSummary(tenantId, runId)) return false;
        ChatRunEntity finalized = runControlService.fail(tenantId, userId, runId, failureReason);
        if (finalized.getStatus() != RunStatus.FAILED) {
            throw new AppException("PARENT_RUN_FINALIZATION_CONFLICT", "父 Agent 运行已被其他终态抢占");
        }
        markParentReady(tenantId, runId, parentDraft);
        return true;
    }

    /** 供流式链路在子任务创建后停止下发普通正文；MySQL 是唯一权威判断。 */
    public boolean isAwaitingSummary(String tenantId, String runId) {
        return parentResumeRepository.isAwaitingSummary(tenantId, runId);
    }

    private void markParentReady(String tenantId, String runId, String parentDraft) {
        String safeDraft = parentDraft == null ? "" : parentDraft;
        if (!parentResumeRepository.markParentReady(tenantId, runId, safeDraft, LocalDateTime.now())) {
            throw new AppException("PARENT_READY_CONFLICT", "父 Agent 汇总屏障状态已变化");
        }
    }
}
