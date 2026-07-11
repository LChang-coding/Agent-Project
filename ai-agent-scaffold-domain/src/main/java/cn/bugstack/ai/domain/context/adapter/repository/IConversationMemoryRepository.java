package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;

/**
 * 会话长期记忆仓储。
 */
public interface IConversationMemoryRepository {

    /**
     * 查询会话有效摘要；参数是会话身份；返回有效摘要或空。
     */
    ConversationMemorySnapshotEntity queryActive(String tenantId, String userId, String sessionId);

    /**
     * 新增记忆摘要；参数是摘要；返回影响行数。
     */
    int insert(ConversationMemorySnapshotEntity snapshot);

    /**
     * 关闭当前摘要；参数是会话身份和版本；返回影响行数。
     */
    int supersede(String tenantId, String userId, String sessionId, Integer memoryVersion);

    /**
     * 激活新摘要；参数是旧摘要版本和新摘要；返回是否激活成功。
     */
    boolean activate(String tenantId, String userId, String sessionId, Integer expectedMemoryVersion, ConversationMemorySnapshotEntity snapshot);
}
