package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatSessionImportPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话导入 DAO。
 */
@Mapper
public interface IChatSessionImportDao {
    int insert(ChatSessionImportPO sessionImport);
    ChatSessionImportPO queryByRecipient(@Param("shareId") String shareId,
                                         @Param("recipientScopeKey") String recipientScopeKey);
}
