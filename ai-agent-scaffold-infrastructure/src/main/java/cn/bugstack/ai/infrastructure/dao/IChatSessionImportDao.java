package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ChatSessionImportPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话导入 DAO。
 */
@Mapper
public interface IChatSessionImportDao {
    /** 幂等登记接收方导入结果；唯一键阻止同一分享重复导入。 */
    int insert(ChatSessionImportPO sessionImport);

    /** 查询指定接收范围是否已导入该分享。 */
    ChatSessionImportPO queryByRecipient(@Param("shareId") String shareId,
                                         @Param("recipientScopeKey") String recipientScopeKey);
}
