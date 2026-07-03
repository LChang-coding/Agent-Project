package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库文档 DAO。
 * <p>负责 `rag_document` 表的基础持久化操作。</p>
 */
@Mapper
public interface IRagDocumentDao {

    /**
     * 新增知识库文档记录。
     *
     * @param ragDocument 知识库文档持久化对象
     * @return 影响行数
     */
    int insert(RagDocumentPO ragDocument);

    /**
     * 按主键更新知识库文档记录。
     *
     * @param ragDocument 知识库文档持久化对象
     * @return 影响行数
     */
    int updateById(RagDocumentPO ragDocument);

    /**
     * 按主键查询知识库文档记录。
     *
     * @param id 主键ID
     * @return 知识库文档持久化对象
     */
    RagDocumentPO queryById(@Param("id") Long id);

    /**
     * 按文档业务ID查询知识库文档记录。
     *
     * @param documentId 文档业务ID
     * @return 知识库文档持久化对象
     */
    RagDocumentPO queryByDocumentId(@Param("documentId") String documentId);

    /**
     * 按租户业务ID查询知识库文档列表。
     *
     * @param tenantId 租户业务ID
     * @return 知识库文档持久化对象列表
     */
    List<RagDocumentPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询知识库文档列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return 知识库文档持久化对象列表
     */
    List<RagDocumentPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * 按知识库业务ID查询知识库文档列表。
     *
     * @param knowledgeBaseId 知识库业务ID
     * @return 知识库文档持久化对象列表
     */
    List<RagDocumentPO> queryListByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    /**
     * 按文档业务ID查询知识库文档列表。
     *
     * @param documentId 文档业务ID
     * @return 知识库文档持久化对象列表
     */
    List<RagDocumentPO> queryListByDocumentId(@Param("documentId") String documentId);

    /**
     * 按租户和可见范围查询知识库文档列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return 知识库文档持久化对象列表
     */
    List<RagDocumentPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
