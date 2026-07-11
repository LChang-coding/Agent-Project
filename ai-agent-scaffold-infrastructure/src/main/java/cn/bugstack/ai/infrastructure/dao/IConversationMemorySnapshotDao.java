package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ConversationMemorySnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话长期记忆 DAO。
 */
@Mapper
public interface IConversationMemorySnapshotDao {
    ConversationMemorySnapshotPO queryActive(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("sessionId") String sessionId);
    int insert(ConversationMemorySnapshotPO snapshot);
    int supersede(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("sessionId") String sessionId, @Param("memoryVersion") Integer memoryVersion);
    int countActiveVersion(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("sessionId") String sessionId, @Param("memoryVersion") Integer memoryVersion);
}
