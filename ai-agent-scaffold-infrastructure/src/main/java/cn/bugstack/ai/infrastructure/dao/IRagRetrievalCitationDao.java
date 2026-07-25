package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalCitationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** RAG 引用审计 DAO。 */
@Mapper
public interface IRagRetrievalCitationDao {
    /** 批量持久化一次检索最终返回给调用方的引用证据。 */
    int insertBatch(@Param("tenantId") String tenantId,
                    @Param("retrievalId") String retrievalId,
                    @Param("citations") List<RagRetrievalCitationPO> citations);
}
