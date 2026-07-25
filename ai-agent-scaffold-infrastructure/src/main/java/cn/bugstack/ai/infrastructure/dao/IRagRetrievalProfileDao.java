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

    /** 查询租户全部未删除检索策略。 */
    List<RagRetrievalProfilePO> queryListByTenant(@Param("tenantId") String tenantId);

    /** 新建检索策略及初始修订号。 */
    int insert(RagRetrievalProfilePO profile);

    /** 以租户和期望修订号更新；返回 0 表示冲突或越权。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("profile") RagRetrievalProfilePO profile,
                                  @Param("expectedRevision") long expectedRevision);
}
