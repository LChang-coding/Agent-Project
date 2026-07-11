package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;

import java.util.List;

/**
 * 会话历史仓储。
 * <p>只暴露上下文管理需要的租户隔离历史查询。</p>
 */
public interface IContextHistoryRepository {

    /**
     * 查询会话消息范围；参数是会话身份和序号范围；返回按序排列的消息。
     */
    List<ChatMessageEntity> queryMessages(String tenantId, String userId, String sessionId,
                                          Integer fromSequenceExclusive, Integer toSequenceInclusive);

    /**
     * 汇总会话消息 token；参数是会话身份和序号范围；返回预估 token 总数。
     */
    int sumEstimatedTokens(String tenantId, String userId, String sessionId,
                           Integer fromSequenceExclusive, Integer toSequenceInclusive);
}
