package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagChunkPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库切片 DAO。
 * <p>负责 `rag_chunk` 表的基础持久化操作。</p>
 */
@Mapper
public interface IRagChunkDao {

    /**
     * 新增知识库切片记录。
     *
     * @param ragChunk 知识库切片持久化对象
     * @return 影响行数
     */
    int insert(RagChunkPO ragChunk);

    /**
     * 按主键更新知识库切片记录。
     *
     * @param ragChunk 知识库切片持久化对象
     * @return 影响行数
     */
    int updateById(RagChunkPO ragChunk);

    /**
     * 按主键查询知识库切片记录。
     *
     * @param id 主键ID
     * @return 知识库切片持久化对象
     */
    RagChunkPO queryById(@Param("id") Long id);

    /**
     * 按切片业务ID查询知识库切片记录。
     *
     * @param chunkId 切片业务ID
     * @return 知识库切片持久化对象
     */
    RagChunkPO queryByChunkId(@Param("chunkId") String chunkId);

    /**
     * 按租户业务ID查询知识库切片列表。
     *
     * @param tenantId 租户业务ID
     * @return 知识库切片持久化对象列表
     */
    List<RagChunkPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询知识库切片列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return 知识库切片持久化对象列表
     */
    List<RagChunkPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * 按知识库业务ID查询知识库切片列表。
     *
     * @param knowledgeBaseId 知识库业务ID
     * @return 知识库切片持久化对象列表
     */
    List<RagChunkPO> queryListByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    /**
     * 按文档业务ID查询知识库切片列表。
     *
     * @param documentId 文档业务ID
     * @return 知识库切片持久化对象列表
     */
    List<RagChunkPO> queryListByDocumentId(@Param("documentId") String documentId);

    /**
     * 按租户和可见范围查询知识库切片列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return 知识库切片持久化对象列表
     */
    List<RagChunkPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
