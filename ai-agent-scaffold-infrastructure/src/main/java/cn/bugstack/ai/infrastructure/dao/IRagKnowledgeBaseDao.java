package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户知识库 DAO。
 */
@Mapper
public interface IRagKnowledgeBaseDao {

    /** 新增知识库。 */
    int insert(RagKnowledgeBasePO knowledgeBase);

    /** 按租户和业务 ID 查询知识库。 */
    RagKnowledgeBasePO queryByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                       @Param("knowledgeBaseId") String knowledgeBaseId);

    /** 查询租户知识库列表。 */
    List<RagKnowledgeBasePO> queryListByTenantId(@Param("tenantId") String tenantId);

    /** 查询租户指定可见范围的知识库。 */
    List<RagKnowledgeBasePO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId,
                                                              @Param("visibility") String visibility);

    /** 按租户、业务 ID 和 revision 乐观更新。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("knowledgeBase") RagKnowledgeBasePO knowledgeBase,
                                  @Param("expectedRevision") long expectedRevision);

    /** 以 CAS 推进知识库的当前可见 generation。 */
    int activateGenerationByTenantAndRevision(@Param("tenantId") String tenantId,
                                               @Param("knowledgeBaseId") String knowledgeBaseId,
                                               @Param("generation") long generation,
                                               @Param("expectedRevision") long expectedRevision);

}
