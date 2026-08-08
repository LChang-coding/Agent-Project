package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;

/**
 * 会话长期记忆仓储。
 */
public interface IConversationMemoryRepository {

    /**
     * 查询会话有效摘要。
     */
    ConversationMemorySnapshotEntity queryActive(String tenantId, String userId, String sessionId);

    /**
     * 新增记忆摘要。
     */
    int insert(ConversationMemorySnapshotEntity snapshot);

    /**
     * 关闭当前摘要。
     */
    int supersede(String tenantId, String userId, String sessionId, Integer memoryVersion);

    /**
     * 激活新摘要。
     */
    boolean activate(String tenantId, String userId, String sessionId, Integer expectedMemoryVersion, ConversationMemorySnapshotEntity snapshot);

    /**
     * 失效覆盖指定消息的快照并恢复安全祖先。
     */
    ConversationMemorySnapshotEntity invalidateCoveringAndRestore(String tenantId, String userId, String sessionId,
                                                                   Integer minInvalidSequence);
}
