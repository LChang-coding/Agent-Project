package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.context.ContextInsightResponseDTO;
import cn.bugstack.ai.api.dto.usage.ModelUsageResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.context.model.ContextInsightEntity;
import cn.bugstack.ai.domain.context.service.ContextInsightService;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话上下文占用与模型 Token 用量查询入口。
 * <p>所有统计来自服务端消息、记忆快照和模型调用账本，不信任浏览器缓存中的估算值。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class SessionInsightController {

    private final ContextInsightService contextInsightService;
    private final ModelUsageService modelUsageService;
    private final SessionDomain sessionDomain;

    /**
     * @param contextInsightService 上下文组装统计服务
     * @param modelUsageService 模型调用用量账本服务
     * @param sessionDomain 会话归属校验服务
     */
    public SessionInsightController(ContextInsightService contextInsightService,
                                    ModelUsageService modelUsageService, SessionDomain sessionDomain) {
        this.contextInsightService = contextInsightService;
        this.modelUsageService = modelUsageService;
        this.sessionDomain = sessionDomain;
    }

    /**
     * 查询一次真实上下文组装后的分类 Token 占用。
     *
     * @param sessionId 会话ID
     * @return 模型窗口、有效 Token、工具、附件和 RAG 占用
     */
    @GetMapping("/sessions/{sessionId}/context-insight")
    public Response<ContextInsightResponseDTO> context(@PathVariable String sessionId) {
        try {
            // 领域服务同时校验会话归属并按当前 contextRevision 组装统计。
            ContextInsightEntity entity = contextInsightService.query(tenantId(), userId(), sessionId);
            return success(toContext(entity));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询上下文洞察失败 sessionId:{}", sessionId, e);
            return systemFail();
        }
    }

    /**
     * 查询会话级和可选运行级模型用量。
     *
     * @param sessionId 会话ID
     * @param runId 可选运行ID
     * @return 最近一次调用、会话汇总和运行汇总
     */
    @GetMapping("/sessions/{sessionId}/model-usage")
    public Response<ModelUsageResponseDTO> sessionUsage(@PathVariable String sessionId,
                                                        @RequestParam(required = false) String runId) {
        try {
            // 在查询用量账本前先确认会话归属，避免凭 runId 探测其他用户数据。
            sessionDomain.assertSessionAccess(tenantId(), userId(), sessionId, null);
            // 最新调用用于展示本轮明细，会话和运行汇总分别支撑累计视图。
            ModelUsageEntity latest = modelUsageService.latest(tenantId(), userId(), sessionId);
            ModelUsageSummaryEntity session = modelUsageService.summarizeSession(tenantId(), userId(), sessionId, null);
            ModelUsageSummaryEntity run = runId == null || runId.isBlank() ? null
                    : modelUsageService.summarizeSession(tenantId(), userId(), sessionId, runId);
            return success(ModelUsageResponseDTO.builder().latest(toLatest(latest)).session(toSummary(session))
                    .run(toSummary(run)).build());
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询会话模型用量失败 sessionId:{}", sessionId, e);
            return systemFail();
        }
    }

    /**
     * 查询当前用户近期 Token 用量。
     *
     * @param days 向前统计天数
     * @return 当前租户和用户维度的聚合
     */
    @GetMapping("/model-usage/summary")
    public Response<ModelUsageResponseDTO> recentUsage(@RequestParam(defaultValue = "1") int days) {
        try {
            return success(ModelUsageResponseDTO.builder()
                    .recent(toSummary(modelUsageService.summarizeRecent(tenantId(), userId(), days))).build());
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询近期模型用量失败 days:{}", days, e);
            return systemFail();
        }
    }

    /** 展开上下文分类统计，保持领域对象不直接暴露给 Web 协议。 */
    private ContextInsightResponseDTO toContext(ContextInsightEntity value) {
        return ContextInsightResponseDTO.builder().sessionId(value.getSessionId())
                .contextRevision(value.getContextRevision()).modelWindowTokens(value.getModelWindowTokens())
                .effectiveTokens(value.getEffectiveTokens()).utilization(value.getUtilization())
                .systemTokens(value.getSystemTokens()).historyTokens(value.getHistoryTokens())
                .summaryTokens(value.getSummaryTokens()).toolResultTokens(value.getToolResultTokens())
                .attachmentTokens(value.getAttachmentTokens()).ragTokens(value.getRagTokens())
                .upstreamTokens(value.getUpstreamTokens()).effectiveFromSequence(value.getEffectiveFromSequence())
                .effectiveToSequence(value.getEffectiveToSequence()).memoryVersion(value.getMemoryVersion())
                .compactionStatus(value.getCompactionStatus()).toolCount(value.getToolCount())
                .callCount(value.getCallCount()).attachmentCount(value.getAttachmentCount())
                .trimReason(value.getTrimReason()).build();
    }

    /** 转换最近一次模型调用；无调用记录时返回 null。 */
    private ModelUsageResponseDTO.LatestCall toLatest(ModelUsageEntity value) {
        if (value == null) {
            return null;
        }
        return ModelUsageResponseDTO.LatestCall.builder().callId(value.getCallId()).runId(value.getRunId())
                .invocationId(value.getInvocationId()).modelVersion(value.getModelVersion())
                .callStatus(value.getCallStatus()).finishReason(value.getFinishReason())
                .promptTokens(value.getPromptTokens()).candidateTokens(value.getCandidateTokens())
                .totalTokens(value.getTotalTokens()).thoughtsTokens(value.getThoughtsTokens())
                .toolUsePromptTokens(value.getToolUsePromptTokens()).createTime(value.getCreateTime()).build();
    }

    /** 转换用量汇总；未指定运行或无数据时返回 null。 */
    private ModelUsageResponseDTO.Summary toSummary(ModelUsageSummaryEntity value) {
        if (value == null) {
            return null;
        }
        return ModelUsageResponseDTO.Summary.builder().callCount(value.getCallCount())
                .successCount(value.getSuccessCount()).failedCount(value.getFailedCount())
                .runningCount(value.getRunningCount()).cancelledCount(value.getCancelledCount())
                .promptTokens(value.getPromptTokens()).candidateTokens(value.getCandidateTokens())
                .totalTokens(value.getTotalTokens()).thoughtsTokens(value.getThoughtsTokens())
                .toolUsePromptTokens(value.getToolUsePromptTokens()).build();
    }

    /** 读取可信租户身份。 */
    private String tenantId() {
        return TenantContextHolder.getTenantId();
    }

    /** 读取可信用户身份，并在认证上下文失效时立即拒绝查询。 */
    private String userId() {
        String value = TenantContextHolder.getUserId();
        if (value == null || value.isBlank()) {
            throw new AppException("AUTH_UNAUTHORIZED", "登录身份已失效");
        }
        return value;
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 将领域异常映射为业务错误响应。 */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /** 隐藏未知异常细节并返回统一系统错误。 */
    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
