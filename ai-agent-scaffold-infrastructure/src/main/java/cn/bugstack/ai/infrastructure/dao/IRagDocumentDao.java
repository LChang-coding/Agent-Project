package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

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

    /** 在删除登记事务中锁定指定知识库的文档聚合根。 */
    RagDocumentPO queryByTenantKnowledgeBaseAndDocumentIdForUpdate(@Param("tenantId") String tenantId,
                                                                   @Param("knowledgeBaseId") String knowledgeBaseId,
                                                                   @Param("documentId") String documentId);

    /** 按租户批量查询业务文档。 */
    List<RagDocumentPO> queryListByTenantAndDocumentIds(@Param("tenantId") String tenantId,
                                                        @Param("documentIds") List<String> documentIds);

    /** 查询租户知识库的文档列表。 */
    List<RagDocumentPO> queryListByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                            @Param("knowledgeBaseId") String knowledgeBaseId);

    int countByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
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

    /** 将目标 generation 原子切换为文档活动版本。 */
    int activateVersionByTenantAndRevision(@Param("tenantId") String tenantId,
                                           @Param("knowledgeBaseId") String knowledgeBaseId,
                                           @Param("documentId") String documentId,
                                           @Param("versionId") String versionId,
                                           @Param("generation") long generation,
                                           @Param("expectedRevision") long expectedRevision,
                                           @Param("pageCount") int pageCount,
                                           @Param("chunkCount") int chunkCount,
                                           @Param("indexedAt") LocalDateTime indexedAt);

    /** 取消或失败时清除本次目标 generation，保留已有活动版本。 */
    int closeTargetGenerationByTenantAndRevision(@Param("tenantId") String tenantId,
                                                  @Param("knowledgeBaseId") String knowledgeBaseId,
                                                  @Param("documentId") String documentId,
                                                  @Param("generation") long generation,
                                                  @Param("expectedRevision") long expectedRevision);

    /** 删除完成事务中以状态和revision关闭文档墓碑。 */
    int markDeletedByTenantAndRevision(@Param("tenantId") String tenantId,
                                       @Param("knowledgeBaseId") String knowledgeBaseId,
                                       @Param("documentId") String documentId,
                                       @Param("expectedRevision") long expectedRevision);

}
