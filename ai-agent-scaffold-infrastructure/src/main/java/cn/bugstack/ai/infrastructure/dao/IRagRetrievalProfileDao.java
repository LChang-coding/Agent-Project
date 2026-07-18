package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalProfilePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户 RAG 检索策略 DAO。
 */
@Mapper
public interface IRagRetrievalProfileDao {
    /** 按租户和策略业务 ID 查询启用配置。 */
    RagRetrievalProfilePO queryByTenantAndProfileId(@Param("tenantId") String tenantId,
                                                    @Param("profileId") String profileId);
}
