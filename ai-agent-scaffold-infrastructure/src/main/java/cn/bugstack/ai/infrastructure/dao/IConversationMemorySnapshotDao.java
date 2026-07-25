package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ConversationMemorySnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话长期记忆 DAO。
 */
@Mapper
public interface IConversationMemorySnapshotDao {
    /** 查询会话当前唯一有效记忆快照。 */
    ConversationMemorySnapshotPO queryActive(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 插入新的版本化记忆快照。 */
    int insert(ConversationMemorySnapshotPO snapshot);

    /** 仅淘汰指定版本，避免覆盖并发生成的新快照。 */
    int supersede(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("sessionId") String sessionId, @Param("memoryVersion") Integer memoryVersion);

    /** 校验指定版本仍是当前有效快照。 */
    int countActiveVersion(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("sessionId") String sessionId, @Param("memoryVersion") Integer memoryVersion);

    /** 标旧覆盖已失效消息序列的快照。 */
    int staleCovering(@Param("tenantId") String tenantId, @Param("userId") String userId,
                      @Param("sessionId") String sessionId, @Param("minInvalidSequence") Integer minInvalidSequence);

    /** 查找失效序列之前最近的安全快照，供取消恢复。 */
    ConversationMemorySnapshotPO queryLatestSafe(@Param("tenantId") String tenantId, @Param("userId") String userId,
                                                 @Param("sessionId") String sessionId,
                                                 @Param("minInvalidSequence") Integer minInvalidSequence);

    /** 按主键重新激活经验证安全的历史快照。 */
    int reactivate(@Param("id") Long id);
}
