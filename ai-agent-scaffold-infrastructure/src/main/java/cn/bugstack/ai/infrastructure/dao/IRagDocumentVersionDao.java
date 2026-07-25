package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagDocumentVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 租户 RAG 文档版本 DAO。
 */
@Mapper
public interface IRagDocumentVersionDao {
    /** 新增文档版本。 */
    int insert(RagDocumentVersionPO version);

    /** 按租户和版本业务 ID 查询。 */
    RagDocumentVersionPO queryByTenantAndVersionId(@Param("tenantId") String tenantId,
                                                   @Param("versionId") String versionId);

    /** 查询租户文档版本列表。 */
    List<RagDocumentVersionPO> queryListByTenantAndDocumentId(@Param("tenantId") String tenantId,
                                                              @Param("documentId") String documentId);

    /** 删除收口事务内锁定文档的完整版本集合。 */
    List<RagDocumentVersionPO> queryListByTenantAndDocumentIdForUpdate(@Param("tenantId") String tenantId,
                                                                       @Param("documentId") String documentId);

    /** 统计知识库尚未完成删除的版本墓碑。 */
    int countNotDeletedByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                   @Param("knowledgeBaseId") String knowledgeBaseId);

    /** 按租户、版本业务 ID 和 row version 乐观更新状态。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("version") RagDocumentVersionPO version,
                                  @Param("expectedRevision") long expectedRevision);

    /** 将已验证的 processing 版本以 CAS 切换为 ready。 */
    int markReadyByTenantAndRevision(@Param("tenantId") String tenantId,
                                     @Param("knowledgeBaseId") String knowledgeBaseId,
                                     @Param("documentId") String documentId,
                                     @Param("versionId") String versionId,
                                     @Param("generation") long generation,
                                     @Param("expectedRevision") long expectedRevision,
                                     @Param("pageCount") int pageCount,
                                     @Param("characterCount") long characterCount,
                                     @Param("chunkCount") int chunkCount,
                                     @Param("parsedBucket") String parsedBucket,
                                     @Param("parsedObjectKey") String parsedObjectKey,
                                     @Param("parsedContentHash") String parsedContentHash,
                                     @Param("parsedSizeBytes") long parsedSizeBytes,
                                     @Param("metadata") String metadata,
                                     @Param("indexedAt") LocalDateTime indexedAt);

    /** 取消或失败时关闭未激活版本。 */
    int closeByTenantAndRevision(@Param("tenantId") String tenantId,
                                 @Param("knowledgeBaseId") String knowledgeBaseId,
                                 @Param("documentId") String documentId,
                                 @Param("versionId") String versionId,
                                 @Param("generation") long generation,
                                 @Param("status") String status,
                                 @Param("expectedRevision") long expectedRevision);

    /** 删除完成事务中以完整聚合范围和revision关闭版本墓碑。 */
    int markDeletedByTenantAndRevision(@Param("tenantId") String tenantId,
                                       @Param("knowledgeBaseId") String knowledgeBaseId,
                                       @Param("documentId") String documentId,
                                       @Param("versionId") String versionId,
                                       @Param("expectedRevision") long expectedRevision);
}
