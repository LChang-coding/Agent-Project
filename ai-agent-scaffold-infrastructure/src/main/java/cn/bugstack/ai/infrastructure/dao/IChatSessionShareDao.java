package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatSessionSharePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话分享 DAO。
 */
@Mapper
public interface IChatSessionShareDao {
    /** 创建分享快照元数据。 */
    int insert(ChatSessionSharePO share);

    /** 以不可逆令牌摘要解析公开分享。 */
    ChatSessionSharePO queryByTokenHash(@Param("tokenHash") String tokenHash);

    /** 在租户和所有者范围内查询分享。 */
    ChatSessionSharePO queryOwnerShare(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("shareId") String shareId);

    /** 悲观锁定分享，串行化访问次数消费。 */
    ChatSessionSharePO lockByShareId(@Param("shareId") String shareId);

    /** 仅在分享仍有效且未达上限时原子增加访问次数。 */
    int consumeAccess(@Param("shareId") String shareId);

    /** 所有者撤销单个分享；返回 0 表示无权或状态已变化。 */
    int revoke(@Param("tenantId") String tenantId, @Param("userId") String userId,
               @Param("shareId") String shareId);

    /** 会话删除前批量撤销其全部分享。 */
    int revokeBySession(@Param("tenantId") String tenantId, @Param("userId") String userId,
                        @Param("sessionId") String sessionId);
}
