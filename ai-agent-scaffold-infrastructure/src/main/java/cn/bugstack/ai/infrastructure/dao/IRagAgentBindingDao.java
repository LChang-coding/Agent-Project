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

    /** 查询租户全部未删除绑定，供管理台展示。 */
    List<RagAgentBindingPO> queryListByTenant(@Param("tenantId") String tenantId);

    /** 在租户边界内查询单个绑定。 */
    RagAgentBindingPO queryByTenantAndBindingId(@Param("tenantId") String tenantId,
                                                @Param("bindingId") String bindingId);

    /** 新建带修订号的绑定。 */
    int insert(RagAgentBindingPO binding);

    /** 以期望修订号软删除；返回 0 表示并发冲突或越权。 */
    int softDeleteByTenantAndRevision(@Param("tenantId") String tenantId,
                                      @Param("bindingId") String bindingId,
                                      @Param("expectedRevision") long expectedRevision);

    /** 知识库删除前禁用租户内全部关联绑定。 */
    int disableByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                          @Param("knowledgeBaseId") String knowledgeBaseId);

    /** 统计仍会参与检索的知识库绑定。 */
    int countActiveByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                               @Param("knowledgeBaseId") String knowledgeBaseId);
}
