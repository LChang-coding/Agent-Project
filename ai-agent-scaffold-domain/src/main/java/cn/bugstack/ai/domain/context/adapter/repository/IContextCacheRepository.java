package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;

import java.time.Duration;
import java.util.List;

/**
 * 上下文缓存仓储。
 * <p>Redis 等缓存实现只能作为可重建缓存，不能承担真相源。</p>
 */
public interface IContextCacheRepository {

    /**
     * 查询缓存中的有效摘要。
     */
    ConversationMemorySnapshotEntity queryActiveSnapshot(String tenantId, String userId, String sessionId);

    /**
     * 缓存有效摘要。
     */
    void cacheActiveSnapshot(ConversationMemorySnapshotEntity snapshot, Duration ttl);

    /**
     * 将已落库消息追加到会话短期窗口。
     */
    void appendRecentMessage(ChatMessageEntity message, int maxMessages, Duration ttl);

    /**
     * 查询会话短期窗口中的指定序号切面。
     */
    List<ChatMessageEntity> queryRecentMessages(String tenantId, String userId, String sessionId,
                                                 Integer fromSequenceExclusive, Integer toSequenceInclusive);

    /**
     * 用原始历史预热会话短期窗口。
     */
    void warmRecentMessages(String tenantId, String userId, String sessionId, List<ChatMessageEntity> messages,
                            int maxMessages, Duration ttl);

    /**
     * 移除已进入长期摘要的短期消息。
     */
    void removeRecentMessagesThrough(String tenantId, String userId, String sessionId, Integer coveredToSequence);

    /**
     * 失效会话缓存。
     */
    void evictSession(String tenantId, String userId, String sessionId);
}
