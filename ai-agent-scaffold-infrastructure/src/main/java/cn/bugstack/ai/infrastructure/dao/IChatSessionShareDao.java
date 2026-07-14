package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatSessionSharePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话分享 DAO。
 */
@Mapper
public interface IChatSessionShareDao {
    int insert(ChatSessionSharePO share);
    ChatSessionSharePO queryByTokenHash(@Param("tokenHash") String tokenHash);
    ChatSessionSharePO queryOwnerShare(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("shareId") String shareId);
    ChatSessionSharePO lockByShareId(@Param("shareId") String shareId);
    int consumeAccess(@Param("shareId") String shareId);
    int revoke(@Param("tenantId") String tenantId, @Param("userId") String userId,
               @Param("shareId") String shareId);
}
