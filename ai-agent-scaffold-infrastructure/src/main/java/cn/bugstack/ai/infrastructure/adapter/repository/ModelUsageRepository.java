package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.usage.adapter.IModelUsageRepository;
import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;
import cn.bugstack.ai.infrastructure.dao.IModelUsageDao;
import cn.bugstack.ai.infrastructure.dao.po.ModelUsagePO;
import cn.bugstack.ai.infrastructure.dao.po.ModelUsageSummaryPO;
import org.springframework.stereotype.Repository;

/**
 * 模型用量 MyBatis 仓储。
 */
@Repository
public class ModelUsageRepository implements IModelUsageRepository {

    private final IModelUsageDao dao;

    /**
     * 创建用量仓储；参数是用量 DAO；返回仓储实例。
     */
    public ModelUsageRepository(IModelUsageDao dao) {
        this.dao = dao;
    }

    @Override
    public int upsert(ModelUsageEntity usage) {
        return dao.upsert(toPO(usage));
    }

    @Override
    public ModelUsageEntity queryLatest(String tenantId, String userId, String sessionId) {
        return toEntity(dao.queryLatest(blankToNull(tenantId), userId, sessionId));
    }

    @Override
    public ModelUsageSummaryEntity summarizeSession(String tenantId, String userId, String sessionId, String runId) {
        return toSummary(dao.summarizeSession(blankToNull(tenantId), userId, sessionId, runId));
    }

    @Override
    public ModelUsageSummaryEntity summarizeRecent(String tenantId, String userId, int days) {
        return toSummary(dao.summarizeRecent(blankToNull(tenantId), userId, days));
    }

    /**
     * 取消运行中的模型调用；参数是可信身份、运行和原因；返回影响行数。
     */
    @Override
    public int cancelRunning(String tenantId, String userId, String sessionId, String runId, String reason) {
        return dao.cancelRunning(blankToNull(tenantId), userId, sessionId, runId, truncate(reason));
    }

    private ModelUsagePO toPO(ModelUsageEntity value) {
        return ModelUsagePO.builder().tenantId(blankToNull(value.getTenantId())).userId(value.getUserId())
                .sessionId(value.getSessionId()).runId(value.getRunId()).callId(value.getCallId())
                .agentId(value.getAgentId()).agentName(value.getAgentName()).appName(value.getAppName())
                .invocationId(value.getInvocationId()).provider(value.getProvider()).modelVersion(value.getModelVersion())
                .usageType(value.getUsageType()).callStatus(value.getCallStatus()).finishReason(value.getFinishReason())
                .promptTokens(value.getPromptTokens()).candidateTokens(value.getCandidateTokens())
                .totalTokens(value.getTotalTokens()).thoughtsTokens(value.getThoughtsTokens())
                .toolUsePromptTokens(value.getToolUsePromptTokens()).traceId(value.getTraceId()).build();
    }

    private ModelUsageEntity toEntity(ModelUsagePO value) {
        if (value == null) {
            return null;
        }
        return ModelUsageEntity.builder().tenantId(value.getTenantId()).userId(value.getUserId())
                .sessionId(value.getSessionId()).runId(value.getRunId()).callId(value.getCallId())
                .invocationId(value.getInvocationId()).agentId(value.getAgentId()).agentName(value.getAgentName())
                .appName(value.getAppName()).provider(value.getProvider()).modelVersion(value.getModelVersion())
                .usageType(value.getUsageType()).callStatus(value.getCallStatus()).finishReason(value.getFinishReason())
                .promptTokens(value.getPromptTokens()).candidateTokens(value.getCandidateTokens())
                .totalTokens(value.getTotalTokens()).thoughtsTokens(value.getThoughtsTokens())
                .toolUsePromptTokens(value.getToolUsePromptTokens()).traceId(value.getTraceId())
                .createTime(value.getCreateTime()).build();
    }

    private ModelUsageSummaryEntity toSummary(ModelUsageSummaryPO value) {
        if (value == null) {
            return null;
        }
        return ModelUsageSummaryEntity.builder().callCount(value.getCallCount()).successCount(value.getSuccessCount())
                .failedCount(value.getFailedCount()).promptTokens(value.getPromptTokens())
                .candidateTokens(value.getCandidateTokens()).totalTokens(value.getTotalTokens())
                .thoughtsTokens(value.getThoughtsTokens()).toolUsePromptTokens(value.getToolUsePromptTokens()).build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "用户取消";
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
