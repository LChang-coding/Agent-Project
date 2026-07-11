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
     * 查询缓存中的有效摘要；参数是会话身份；返回摘要或空。
     */
    ConversationMemorySnapshotEntity queryActiveSnapshot(String tenantId, String userId, String sessionId);

    /**
     * 缓存有效摘要；参数是摘要和过期时间；无返回值。
     */
    void cacheActiveSnapshot(ConversationMemorySnapshotEntity snapshot, Duration ttl);

    /**
     * 将已落库消息追加到会话短期窗口；参数是消息、窗口条数和过期时间；无返回值。
     */
    void appendRecentMessage(ChatMessageEntity message, int maxMessages, Duration ttl);

    /**
     * 查询会话短期窗口中的指定序号切面；参数是会话身份和序号范围；返回消息列表，缓存未命中时返回空值。
     */
    List<ChatMessageEntity> queryRecentMessages(String tenantId, String userId, String sessionId,
                                                 Integer fromSequenceExclusive, Integer toSequenceInclusive);

    /**
     * 用原始历史预热会话短期窗口；参数是会话身份、消息、窗口条数和过期时间；无返回值。
     */
    void warmRecentMessages(String tenantId, String userId, String sessionId, List<ChatMessageEntity> messages,
                            int maxMessages, Duration ttl);

    /**
     * 移除已进入长期摘要的短期消息；参数是会话身份和已覆盖序号；无返回值。
     */
    void removeRecentMessagesThrough(String tenantId, String userId, String sessionId, Integer coveredToSequence);

    /**
     * 失效会话缓存；参数是会话身份；无返回值。
     */
    void evictSession(String tenantId, String userId, String sessionId);
}
