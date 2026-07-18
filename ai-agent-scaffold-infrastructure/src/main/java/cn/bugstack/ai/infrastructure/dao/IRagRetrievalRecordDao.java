package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalRecordPO;
import org.apache.ibatis.annotations.Mapper;

/** RAG 检索审计 DAO。 */
@Mapper
public interface IRagRetrievalRecordDao {
    int insert(RagRetrievalRecordPO record);
}
