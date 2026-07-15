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
 * 会话上下文与模型用量接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class SessionInsightController {

    private final ContextInsightService contextInsightService;
    private final ModelUsageService modelUsageService;
    private final SessionDomain sessionDomain;

    /**
     * 创建会话洞察接口；参数是上下文、用量和会话服务；返回接口实例。
     */
    public SessionInsightController(ContextInsightService contextInsightService,
                                    ModelUsageService modelUsageService, SessionDomain sessionDomain) {
        this.contextInsightService = contextInsightService;
        this.modelUsageService = modelUsageService;
        this.sessionDomain = sessionDomain;
    }

    /**
     * 查询真实上下文占用；参数是会话ID；返回只读组装统计。
     */
    @GetMapping("/sessions/{sessionId}/context-insight")
    public Response<ContextInsightResponseDTO> context(@PathVariable String sessionId) {
        try {
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
     * 查询会话与运行 Token 用量；参数是会话和可选运行；返回最新调用及聚合。
     */
    @GetMapping("/sessions/{sessionId}/model-usage")
    public Response<ModelUsageResponseDTO> sessionUsage(@PathVariable String sessionId,
                                                        @RequestParam(required = false) String runId) {
        try {
            sessionDomain.assertSessionAccess(tenantId(), userId(), sessionId, null);
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
     * 查询当前用户近期 Token 用量；参数是统计天数；返回聚合。
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

    private String tenantId() {
        return TenantContextHolder.getTenantId();
    }

    private String userId() {
        String value = TenantContextHolder.getUserId();
        if (value == null || value.isBlank()) {
            throw new AppException("AUTH_UNAUTHORIZED", "登录身份已失效");
        }
        return value;
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
