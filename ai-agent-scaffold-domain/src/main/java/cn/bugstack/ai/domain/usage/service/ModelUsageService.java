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

    /** 用量终态与聚合查询仓储。 */
    private final IModelUsageRepository repository;

    /**
     * 创建模型用量服务。
     */
    public ModelUsageService(IModelUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 幂等保存调用终态。
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
     * 查询会话最新调用。
     */
    public ModelUsageEntity latest(String tenantId, String userId, String sessionId) {
        return repository.queryLatest(tenantId, userId, sessionId);
    }

    /**
     * 聚合会话或运行用量。
     */
    public ModelUsageSummaryEntity summarizeSession(String tenantId, String userId, String sessionId, String runId) {
        return defaultSummary(repository.summarizeSession(tenantId, userId, sessionId, runId));
    }

    /**
     * 聚合近期用户用量。
     */
    public ModelUsageSummaryEntity summarizeRecent(String tenantId, String userId, int days) {
        if (days < 1 || days > 90) {
            throw new AppException("MODEL_USAGE_RANGE_INVALID", "统计天数必须在 1 到 90 之间");
        }
        return defaultSummary(repository.summarizeRecent(tenantId, userId, days));
    }

    /**
     * 将运行中的模型调用标记取消。
     */
    public int cancelRunning(String tenantId, String userId, String sessionId, String runId, String reason) {
        if (blank(userId) || blank(sessionId) || blank(runId)) {
            return 0;
        }
        return repository.cancelRunning(tenantId, userId, sessionId, runId, reason);
    }

    /** 将无记录和空聚合列统一转换为零值。 */
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

    /** 空 Integer Token 按零处理。 */
    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    /** 空 Long 聚合值按零处理。 */
    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    /** Token 只允许空值或非负值。 */
    private boolean negative(Integer value) {
        return value != null && value < 0;
    }

    /** 判断调用身份字段是否缺失。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
