package cn.bugstack.ai.domain.asset.adapter;

import cn.bugstack.ai.domain.asset.model.AssetEntity;

import java.util.List;

/**
 * 资产仓储接口。
 * <p>所有读写都必须携带租户和拥有者范围。</p>
 */
public interface IAssetRepository {

    /** 保存资产引用；参数是资产实体；返回保存后的资产。 */
    AssetEntity insert(AssetEntity asset);

    /** 查询当前用户可访问资产；参数是可信身份和资产ID；返回资产。 */
    AssetEntity queryOwned(String tenantId, String ownerUserId, String assetId);

    /** 查询相同内容的已有对象；参数是可信身份和哈希；返回可复用资产。 */
    AssetEntity queryReusableByHash(String tenantId, String ownerUserId, String sha256);

    /** 分页查询资产；参数是可信身份、游标、数量、会话和类型；返回资产列表。 */
    List<AssetEntity> queryOwnedList(String tenantId, String ownerUserId, Long cursor, int limit,
                                     String sessionId, String assetKind);

    /** 原子绑定本次消息的附件；参数是可信身份、会话、消息和附件ID；返回更新数量。 */
    int bindReadyAssets(String tenantId, String ownerUserId, String sessionId, String messageId,
                        List<String> assetIds);

    /** 软删除资产；参数是可信身份和资产ID；返回更新数量。 */
    int softDelete(String tenantId, String ownerUserId, String assetId);

    /** 查询上下文可用附件；参数是可信身份、会话和可选运行；返回有效消息附件。 */
    List<AssetEntity> queryContextAssets(String tenantId, String ownerUserId, String sessionId,
                                         Integer visibleThroughSequence);
}
