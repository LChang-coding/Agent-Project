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

    /** 读写模型调用明细并执行聚合统计。 */
    private final IModelUsageDao dao;

    /** 注入模型用量 DAO。 */
    public ModelUsageRepository(IModelUsageDao dao) {
        this.dao = dao;
    }

    /** 按调用唯一键新增或更新模型 Token 用量。 */
    @Override
    public int upsert(ModelUsageEntity usage) {
        return dao.upsert(toPO(usage));
    }

    /** 查询会话最近一次模型调用。 */
    @Override
    public ModelUsageEntity queryLatest(String tenantId, String userId, String sessionId) {
        return toEntity(dao.queryLatest(blankToNull(tenantId), userId, sessionId));
    }

    /** 聚合一个会话或指定运行的调用状态和 Token 用量。 */
    @Override
    public ModelUsageSummaryEntity summarizeSession(String tenantId, String userId, String sessionId, String runId) {
        return toSummary(dao.summarizeSession(blankToNull(tenantId), userId, sessionId, runId));
    }

    /** 聚合用户最近指定天数的调用状态和 Token 用量。 */
    @Override
    public ModelUsageSummaryEntity summarizeRecent(String tenantId, String userId, int days) {
        return toSummary(dao.summarizeRecent(blankToNull(tenantId), userId, days));
    }

    /** 将可信运行范围内仍为 running 的模型调用批量标记为取消。 */
    @Override
    public int cancelRunning(String tenantId, String userId, String sessionId, String runId, String reason) {
        return dao.cancelRunning(blankToNull(tenantId), userId, sessionId, runId, truncate(reason));
    }

    /** 将模型调用领域实体复制到持久化对象。 */
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

    /** 将数据库模型调用记录恢复为领域实体。 */
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

    /** 将数据库聚合结果恢复为领域统计对象。 */
    private ModelUsageSummaryEntity toSummary(ModelUsageSummaryPO value) {
        if (value == null) {
            return null;
        }
        return ModelUsageSummaryEntity.builder().callCount(value.getCallCount()).successCount(value.getSuccessCount())
                .failedCount(value.getFailedCount()).runningCount(value.getRunningCount())
                .cancelledCount(value.getCancelledCount()).promptTokens(value.getPromptTokens())
                .candidateTokens(value.getCandidateTokens()).totalTokens(value.getTotalTokens())
                .thoughtsTokens(value.getThoughtsTokens()).toolUsePromptTokens(value.getToolUsePromptTokens()).build();
    }

    /** 将空白租户标识规范化为空值，兼容历史单租户记录。 */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 把取消原因限制在 128 字符内，空原因使用稳定默认值。 */
    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "用户取消";
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
