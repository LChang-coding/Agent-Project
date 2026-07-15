package cn.bugstack.ai.domain.usage.service;

import cn.bugstack.ai.domain.usage.adapter.IModelUsageRepository;
import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * 模型用量领域服务。
 * <p>负责调用终态幂等落库和可信范围聚合。</p>
 */
@Service
public class ModelUsageService {

    private final IModelUsageRepository repository;

    /**
     * 创建模型用量服务；参数是用量仓储；返回服务实例。
     */
    public ModelUsageService(IModelUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 幂等保存调用终态；参数是模型用量；返回影响行数。
     */
    public int record(ModelUsageEntity usage) {
        if (usage == null || blank(usage.getUserId()) || blank(usage.getSessionId()) || blank(usage.getCallId())
                || blank(usage.getInvocationId()) || blank(usage.getCallStatus())) {
            throw new AppException("MODEL_USAGE_INVALID", "模型用量缺少调用身份或终态");
        }
        if (!("running".equals(usage.getCallStatus()) || "success".equals(usage.getCallStatus()) || "failed".equals(usage.getCallStatus())
                || "cancelled".equals(usage.getCallStatus())) || negative(usage.getPromptTokens())
                || negative(usage.getCandidateTokens()) || negative(usage.getTotalTokens())
                || negative(usage.getThoughtsTokens()) || negative(usage.getToolUsePromptTokens())) {
            throw new AppException("MODEL_USAGE_INVALID", "模型用量状态或 Token 数量非法");
        }
        if (usage.getTotalTokens() == null && (usage.getPromptTokens() != null || usage.getCandidateTokens() != null)) {
            usage.setTotalTokens(safe(usage.getPromptTokens()) + safe(usage.getCandidateTokens()));
        }
        return repository.upsert(usage);
    }

    /**
     * 查询会话最新调用；参数是可信身份和会话；返回最新用量。
     */
    public ModelUsageEntity latest(String tenantId, String userId, String sessionId) {
        return repository.queryLatest(tenantId, userId, sessionId);
    }

    /**
     * 聚合会话或运行用量；参数是可信身份、会话和运行；返回聚合结果。
     */
    public ModelUsageSummaryEntity summarizeSession(String tenantId, String userId, String sessionId, String runId) {
        return defaultSummary(repository.summarizeSession(tenantId, userId, sessionId, runId));
    }

    /**
     * 聚合近期用户用量；参数是可信身份和天数；返回聚合结果。
     */
    public ModelUsageSummaryEntity summarizeRecent(String tenantId, String userId, int days) {
        if (days < 1 || days > 90) {
            throw new AppException("MODEL_USAGE_RANGE_INVALID", "统计天数必须在 1 到 90 之间");
        }
        return defaultSummary(repository.summarizeRecent(tenantId, userId, days));
    }

    /**
     * 将运行中的模型调用标记取消；参数是可信身份、会话、运行和原因；返回影响行数。
     */
    public int cancelRunning(String tenantId, String userId, String sessionId, String runId, String reason) {
        if (blank(userId) || blank(sessionId) || blank(runId)) {
            return 0;
        }
        return repository.cancelRunning(tenantId, userId, sessionId, runId, reason);
    }

    private ModelUsageSummaryEntity defaultSummary(ModelUsageSummaryEntity value) {
        return ModelUsageSummaryEntity.builder()
                .callCount(value == null ? 0L : safe(value.getCallCount()))
                .successCount(value == null ? 0L : safe(value.getSuccessCount()))
                .failedCount(value == null ? 0L : safe(value.getFailedCount()))
                .runningCount(value == null ? 0L : safe(value.getRunningCount()))
                .cancelledCount(value == null ? 0L : safe(value.getCancelledCount()))
                .promptTokens(value == null ? 0L : safe(value.getPromptTokens()))
                .candidateTokens(value == null ? 0L : safe(value.getCandidateTokens()))
                .totalTokens(value == null ? 0L : safe(value.getTotalTokens()))
                .thoughtsTokens(value == null ? 0L : safe(value.getThoughtsTokens()))
                .toolUsePromptTokens(value == null ? 0L : safe(value.getToolUsePromptTokens()))
                .build();
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private boolean negative(Integer value) {
        return value != null && value < 0;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
