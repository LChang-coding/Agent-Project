package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息 DAO。
 * <p>负责 `chat_message` 表的基础持久化操作。</p>
 */
@Mapper
public interface IChatMessageDao {

    /**
     * 新增消息记录。
     *
     * @param chatMessage 消息持久化对象
     * @return 影响行数
     */
    int insert(ChatMessagePO chatMessage);

    /**
     * 按主键更新消息记录。
     *
     * @param chatMessage 消息持久化对象
     * @return 影响行数
     */
    int updateById(ChatMessagePO chatMessage);

    /**
     * 按主键查询消息记录。
     *
     * @param id 主键ID
     * @return 消息持久化对象
     */
    ChatMessagePO queryById(@Param("id") Long id);

    /**
     * 按消息业务ID查询消息记录。
     *
     * @param messageId 消息业务ID
     * @return 消息持久化对象
     */
    ChatMessagePO queryByMessageId(@Param("messageId") String messageId);

    /**
     * 按租户业务ID查询消息列表。
     *
     * @param tenantId 租户业务ID
     * @return 消息持久化对象列表
     */
    List<ChatMessagePO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询消息列表。
     *
     * @param userId 用户业务ID
     * @return 消息持久化对象列表
     */
    List<ChatMessagePO> queryListByUserId(@Param("userId") String userId);

    /**
     * 按会话业务ID查询消息列表。
     *
     * @param sessionId 会话业务ID
     * @return 消息持久化对象列表
     */
    List<ChatMessagePO> queryListBySessionId(@Param("sessionId") String sessionId);

    /**
     * 查询会话内最大消息序号。
     *
     * @param tenantId 租户业务ID
     * @param userId 用户业务ID
     * @param sessionId 会话业务ID
     * @return 最大消息序号
     */
    Integer queryMaxSequenceNo(@Param("tenantId") String tenantId,
                               @Param("userId") String userId,
                               @Param("sessionId") String sessionId);
}
