package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.SessionRagBindingSelectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话RAG手动绑定选择DAO。
 */
@Mapper
public interface ISessionRagBindingSelectionDao {

    /** 查询可信会话范围内的选择。 */
    List<SessionRagBindingSelectionPO> queryBySession(@Param("tenantId") String tenantId,
                                                       @Param("userId") String userId,
                                                       @Param("sessionId") String sessionId);

    /** 删除可信会话范围内的旧选择。 */
    int deleteBySession(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("sessionId") String sessionId);

    /** 批量写入新的会话选择。 */
    int batchInsert(@Param("items") List<SessionRagBindingSelectionPO> items);
}
