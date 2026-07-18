package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagRetrievalProfilePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户 RAG 检索策略 DAO。
 */
@Mapper
public interface IRagRetrievalProfileDao {
    /** 按租户和策略业务 ID 查询启用配置。 */
    RagRetrievalProfilePO queryByTenantAndProfileId(@Param("tenantId") String tenantId,
                                                    @Param("profileId") String profileId);

    List<RagRetrievalProfilePO> queryListByTenant(@Param("tenantId") String tenantId);

    int insert(RagRetrievalProfilePO profile);

    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("profile") RagRetrievalProfilePO profile,
                                  @Param("expectedRevision") long expectedRevision);
}
