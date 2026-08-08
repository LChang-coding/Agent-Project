package cn.bugstack.ai.domain.usage.adapter;

import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;

/**
 * 模型用量仓储契约。
 */
public interface IModelUsageRepository {

    /**
     * 幂等保存模型调用终态。
     */
    int upsert(ModelUsageEntity usage);

    /**
     * 查询会话最新调用。
     */
    ModelUsageEntity queryLatest(String tenantId, String userId, String sessionId);

    /**
     * 聚合会话用量。
     */
    ModelUsageSummaryEntity summarizeSession(String tenantId, String userId, String sessionId, String runId);

    /**
     * 聚合用户指定天数用量。
     */
    ModelUsageSummaryEntity summarizeRecent(String tenantId, String userId, int days);

    /**
     * 取消运行中的模型调用。
     */
    int cancelRunning(String tenantId, String userId, String sessionId, String runId, String reason);
}
