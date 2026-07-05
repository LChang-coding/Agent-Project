package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话 DAO。
 * <p>负责 `chat_session` 表的基础持久化操作。</p>
 */
@Mapper
public interface IChatSessionDao {

    /**
     * 新增会话记录。
     *
     * @param chatSession 会话持久化对象
     * @return 影响行数
     */
    int insert(ChatSessionPO chatSession);

    /**
     * 按主键更新会话记录。
     *
     * @param chatSession 会话持久化对象
     * @return 影响行数
     */
    int updateById(ChatSessionPO chatSession);

    /**
     * 按主键查询会话记录。
     *
     * @param id 主键ID
     * @return 会话持久化对象
     */
    ChatSessionPO queryById(@Param("id") Long id);

    /**
     * 按会话业务ID查询会话记录。
     *
     * @param sessionId 会话业务ID
     * @return 会话持久化对象
     */
    ChatSessionPO queryBySessionId(@Param("sessionId") String sessionId);

    /**
     * 按租户、用户和会话ID查询会话记录。
     *
     * @param tenantId 租户业务ID
     * @param userId 用户业务ID
     * @param sessionId 会话业务ID
     * @return 会话持久化对象
     */
    ChatSessionPO queryByTenantUserSession(@Param("tenantId") String tenantId,
                                           @Param("userId") String userId,
                                           @Param("sessionId") String sessionId);

    /**
     * 按租户、用户和会话ID锁定会话记录。
     *
     * @param tenantId 租户业务ID
     * @param userId 用户业务ID
     * @param sessionId 会话业务ID
     * @return 会话持久化对象
     */
    ChatSessionPO lockByTenantUserSession(@Param("tenantId") String tenantId,
                                          @Param("userId") String userId,
                                          @Param("sessionId") String sessionId);

    /**
     * 更新会话最后消息时间。
     *
     * @param tenantId 租户业务ID
     * @param userId 用户业务ID
     * @param sessionId 会话业务ID
     * @param lastMessageTime 最后消息时间
     * @return 影响行数
     */
    int updateLastMessageTime(@Param("tenantId") String tenantId,
                              @Param("userId") String userId,
                              @Param("sessionId") String sessionId,
                              @Param("lastMessageTime") LocalDateTime lastMessageTime);

    /**
     * 按租户业务ID查询会话列表。
     *
     * @param tenantId 租户业务ID
     * @return 会话持久化对象列表
     */
    List<ChatSessionPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询会话列表。
     *
     * @param userId 用户业务ID
     * @return 会话持久化对象列表
     */
    List<ChatSessionPO> queryListByUserId(@Param("userId") String userId);

    /**
     * 按会话业务ID查询会话列表。
     *
     * @param sessionId 会话业务ID
     * @return 会话持久化对象列表
     */
    List<ChatSessionPO> queryListBySessionId(@Param("sessionId") String sessionId);
}
