package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;

import java.util.List;

/**
 * 会话历史仓储。
 * <p>只暴露上下文管理需要的租户隔离历史查询。</p>
 */
public interface IContextHistoryRepository {

    /**
     * 查询会话消息范围。
     */
    List<ChatMessageEntity> queryMessages(String tenantId, String userId, String sessionId,
                                          Integer fromSequenceExclusive, Integer toSequenceInclusive);

    /**
     * 汇总会话消息 token。
     */
    int sumEstimatedTokens(String tenantId, String userId, String sessionId,
                           Integer fromSequenceExclusive, Integer toSequenceInclusive);
}
