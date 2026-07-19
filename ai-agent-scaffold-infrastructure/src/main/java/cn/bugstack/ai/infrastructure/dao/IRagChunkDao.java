package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagChunkPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户 RAG 分块 DAO。
 */
@Mapper
public interface IRagChunkDao {

    /** 幂等批量写入指定文档版本的分块。 */
    int upsertBatch(@Param("tenantId") String tenantId,
                    @Param("versionId") String versionId,
                    @Param("chunks") List<RagChunkPO> chunks);

    /** 查询租户文档版本的全部有效分块。 */
    List<RagChunkPO> queryListByTenantAndVersionId(@Param("tenantId") String tenantId,
                                                   @Param("versionId") String versionId);

    /** 按租户批量查询有效分块。 */
    List<RagChunkPO> queryListByTenantAndChunkIds(@Param("tenantId") String tenantId,
                                                  @Param("chunkIds") List<String> chunkIds);

    /** 软删除租户文档版本的分块。 */
    int softDeleteByTenantAndVersionId(@Param("tenantId") String tenantId,
                                       @Param("versionId") String versionId);

    /** 物理删除租户文档版本的全部分块。 */
    int deleteByTenantAndVersionId(@Param("tenantId") String tenantId,
                                   @Param("versionId") String versionId);

    /** 统计租户文档版本的全部分块，包含已软删记录。 */
    long countAllByTenantAndVersionId(@Param("tenantId") String tenantId,
                                      @Param("versionId") String versionId);

    /** 按租户和切片业务 ID 查询。 */
    RagChunkPO queryByTenantAndChunkId(@Param("tenantId") String tenantId,
                                      @Param("chunkId") String chunkId);

    /** 兼容平台批量地基的单行新增。 */
    int insert(RagChunkPO chunk);

    /** 查询租户全部分块。 */
    List<RagChunkPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /** 查询租户指定可见范围的分块。 */
    List<RagChunkPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId,
                                                      @Param("visibility") String visibility);

}
