package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagAgentBindingPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户 RAG 目标绑定 DAO。
 */
@Mapper
public interface IRagAgentBindingDao {
    /** 查询租户指定目标的有效知识库绑定。 */
    List<RagAgentBindingPO> queryActiveByTenantAndTarget(@Param("tenantId") String tenantId,
                                                         @Param("targetType") String targetType,
                                                         @Param("targetId") String targetId);
}
