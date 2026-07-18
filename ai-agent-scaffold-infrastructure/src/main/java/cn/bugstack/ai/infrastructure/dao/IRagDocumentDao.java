package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户 RAG 逻辑文档 DAO。
 */
@Mapper
public interface IRagDocumentDao {

    /** 新增逻辑文档。 */
    int insert(RagDocumentPO document);

    /** 按租户和业务 ID 查询文档。 */
    RagDocumentPO queryByTenantAndDocumentId(@Param("tenantId") String tenantId,
                                             @Param("documentId") String documentId);

    /** 查询租户知识库的文档列表。 */
    List<RagDocumentPO> queryListByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                            @Param("knowledgeBaseId") String knowledgeBaseId);

    /** 查询租户全部文档。 */
    List<RagDocumentPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /** 查询租户指定可见范围的文档。 */
    List<RagDocumentPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId,
                                                         @Param("visibility") String visibility);

    /** 按租户、业务 ID 和 revision 乐观更新。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("document") RagDocumentPO document,
                                  @Param("expectedRevision") long expectedRevision);

}
