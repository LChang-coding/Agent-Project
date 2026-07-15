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

    /** 按可信拥有者范围查询资产；参数是租户、用户和资产ID；返回资产。 */
    ArtifactAssetPO queryOwned(@Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId,
                               @Param("assetId") String assetId);

    /** 查询同一拥有者可复用对象；参数是租户、用户和哈希；返回最近资产。 */
    ArtifactAssetPO queryReusableByHash(@Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId,
                                        @Param("sha256") String sha256);

    /** 分页查询拥有者资产；参数是可信范围和过滤条件；返回资产列表。 */
    List<ArtifactAssetPO> queryOwnedList(@Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId,
                                         @Param("cursor") Long cursor, @Param("limit") int limit,
                                         @Param("sessionId") String sessionId, @Param("assetKind") String assetKind);

    /** 批量绑定 ready 附件；参数是可信消息范围和资产ID；返回更新数量。 */
    int bindReadyAssets(@Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId,
                        @Param("sessionId") String sessionId, @Param("messageId") String messageId,
                        @Param("assetIds") List<String> assetIds);

    /** 软删除拥有者资产；参数是可信范围和资产ID；返回更新数量。 */
    int softDeleteOwned(@Param("tenantId") String tenantId, @Param("ownerUserId") String ownerUserId,
                        @Param("assetId") String assetId);

    /** 查询有效消息关联附件；参数是可信会话和可选运行；返回可注入附件。 */
    List<ArtifactAssetPO> queryContextAssets(@Param("tenantId") String tenantId,
                                             @Param("ownerUserId") String ownerUserId,
                                             @Param("sessionId") String sessionId,
                                             @Param("fromSequenceExclusive") Integer fromSequenceExclusive,
                                             @Param("visibleThroughSequence") Integer visibleThroughSequence);

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
