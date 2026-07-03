package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.ArtifactAssetPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 资产 DAO。
 * <p>负责 `artifact_asset` 表的基础持久化操作。</p>
 */
@Mapper
public interface IArtifactAssetDao {

    /**
     * 新增资产记录。
     *
     * @param artifactAsset 资产持久化对象
     * @return 影响行数
     */
    int insert(ArtifactAssetPO artifactAsset);

    /**
     * 按主键更新资产记录。
     *
     * @param artifactAsset 资产持久化对象
     * @return 影响行数
     */
    int updateById(ArtifactAssetPO artifactAsset);

    /**
     * 按主键查询资产记录。
     *
     * @param id 主键ID
     * @return 资产持久化对象
     */
    ArtifactAssetPO queryById(@Param("id") Long id);

    /**
     * 按资产业务ID查询资产记录。
     *
     * @param assetId 资产业务ID
     * @return 资产持久化对象
     */
    ArtifactAssetPO queryByAssetId(@Param("assetId") String assetId);

    /**
     * 按租户业务ID查询资产列表。
     *
     * @param tenantId 租户业务ID
     * @return 资产持久化对象列表
     */
    List<ArtifactAssetPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询资产列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return 资产持久化对象列表
     */
    List<ArtifactAssetPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * 按会话业务ID查询资产列表。
     *
     * @param sessionId 会话业务ID
     * @return 资产持久化对象列表
     */
    List<ArtifactAssetPO> queryListBySessionId(@Param("sessionId") String sessionId);

    /**
     * 按租户和可见范围查询资产列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return 资产持久化对象列表
     */
    List<ArtifactAssetPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
