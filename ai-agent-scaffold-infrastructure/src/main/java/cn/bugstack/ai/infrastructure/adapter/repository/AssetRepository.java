package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao;
import cn.bugstack.ai.infrastructure.dao.po.ArtifactAssetPO;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis 资产仓储实现。
 */
@Repository
public class AssetRepository implements IAssetRepository {

    /** 读写资产元数据及消息绑定关系。 */
    private final IArtifactAssetDao dao;

    /** 注入资产 DAO。 */
    public AssetRepository(IArtifactAssetDao dao) {
        this.dao = dao;
    }

    /** 保存资产元数据，并把数据库生成的主键回填到领域实体。 */
    @Override
    public AssetEntity insert(AssetEntity asset) {
        ArtifactAssetPO po = toPO(asset);
        dao.insert(po);
        asset.setId(po.getId());
        return asset;
    }

    /** 按可信租户和拥有者查询资产，防止跨用户读取。 */
    @Override
    public AssetEntity queryOwned(String tenantId, String ownerUserId, String assetId) {
        return toEntity(dao.queryOwned(tenantId, ownerUserId, assetId));
    }

    /** 按文件摘要查询当前拥有者可以复用的就绪资产。 */
    @Override
    public AssetEntity queryReusableByHash(String tenantId, String ownerUserId, String sha256) {
        return toEntity(dao.queryReusableByHash(tenantId, ownerUserId, sha256));
    }

    /** 按游标分页查询拥有者资产，并应用可选会话和类型过滤。 */
    @Override
    public List<AssetEntity> queryOwnedList(String tenantId, String ownerUserId, Long cursor, int limit,
                                            String sessionId, String assetKind) {
        return dao.queryOwnedList(tenantId, ownerUserId, cursor, limit, sessionId, assetKind)
                .stream().map(this::toEntity).toList();
    }

    /** 将当前拥有者的就绪资产批量绑定到会话消息。 */
    @Override
    public int bindReadyAssets(String tenantId, String ownerUserId, String sessionId, String messageId,
                               List<String> assetIds) {
        return dao.bindReadyAssets(tenantId, ownerUserId, sessionId, messageId, assetIds);
    }

    /** 只软删除当前租户和拥有者范围内的资产。 */
    @Override
    public int softDelete(String tenantId, String ownerUserId, String assetId) {
        return dao.softDeleteOwned(tenantId, ownerUserId, assetId);
    }

    /** 查询给定消息序号窗口内可以注入上下文的附件内容。 */
    @Override
    public List<AssetEntity> queryContextAssets(String tenantId, String ownerUserId, String sessionId,
                                                Integer fromSequenceExclusive, Integer visibleThroughSequence,
                                                int candidateLimit, int maxContentChars) {
        return dao.queryContextAssets(tenantId, ownerUserId, sessionId, fromSequenceExclusive,
                        visibleThroughSequence, candidateLimit, maxContentChars)
                .stream().map(this::toEntity).toList();
    }

    /** 统计可信会话和消息序号窗口内可用的上下文附件。 */
    @Override
    public int countContextAssets(String tenantId, String ownerUserId, String sessionId,
                                  Integer fromSequenceExclusive, Integer visibleThroughSequence) {
        return dao.countContextAssets(tenantId, ownerUserId, sessionId, fromSequenceExclusive,
                visibleThroughSequence);
    }

    /** 将资产领域实体复制到持久化对象。 */
    private ArtifactAssetPO toPO(AssetEntity value) {
        ArtifactAssetPO po = ArtifactAssetPO.builder().tenantId(value.getTenantId())
                .ownerUserId(value.getOwnerUserId()).visibility(value.getVisibility()).sessionId(value.getSessionId())
                .messageId(value.getMessageId()).assetId(value.getAssetId()).assetKind(value.getAssetKind())
                .assetType(value.getAssetType()).bucket(value.getBucket()).objectKey(value.getObjectKey())
                .fileName(value.getFileName()).mimeType(value.getMimeType()).sizeBytes(value.getSizeBytes())
                .sha256(value.getSha256()).status(value.getStatus()).parseStatus(value.getParseStatus())
                .extractedText(value.getExtractedText()).parseError(value.getParseError()).metadata(value.getMetadata()).build();
        po.setId(value.getId());
        return po;
    }

    /** 将数据库资产记录恢复为领域实体，未查询到时返回空值。 */
    private AssetEntity toEntity(ArtifactAssetPO value) {
        if (value == null) return null;
        return AssetEntity.builder().id(value.getId()).tenantId(value.getTenantId()).ownerUserId(value.getOwnerUserId())
                .visibility(value.getVisibility()).sessionId(value.getSessionId()).messageId(value.getMessageId())
                .assetId(value.getAssetId()).assetKind(value.getAssetKind()).assetType(value.getAssetType())
                .bucket(value.getBucket()).objectKey(value.getObjectKey()).fileName(value.getFileName())
                .mimeType(value.getMimeType()).sizeBytes(value.getSizeBytes()).sha256(value.getSha256())
                .status(value.getStatus()).parseStatus(value.getParseStatus()).extractedText(value.getExtractedText())
                .parseError(value.getParseError()).metadata(value.getMetadata()).createTime(value.getCreateTime())
                .updateTime(value.getUpdateTime()).build();
    }
}
