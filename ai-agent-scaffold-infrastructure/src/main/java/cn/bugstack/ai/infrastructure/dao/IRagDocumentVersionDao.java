package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagDocumentVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    /** 按租户、版本业务 ID 和 row version 乐观更新状态。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("version") RagDocumentVersionPO version,
                                  @Param("expectedRevision") long expectedRevision);
}
