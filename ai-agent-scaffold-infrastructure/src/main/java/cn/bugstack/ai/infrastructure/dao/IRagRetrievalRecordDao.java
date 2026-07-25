package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalRecordPO;
import org.apache.ibatis.annotations.Mapper;

/** RAG 检索审计 DAO。 */
@Mapper
public interface IRagRetrievalRecordDao {
    /** 写入一次检索的策略、耗时、降级和命中摘要。 */
    int insert(RagRetrievalRecordPO record);
}
