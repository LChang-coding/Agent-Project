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

    private final IArtifactAssetDao dao;

    /** 创建资产仓储；参数是资产 DAO；返回仓储实例。 */
    public AssetRepository(IArtifactAssetDao dao) {
        this.dao = dao;
    }

    @Override
    public AssetEntity insert(AssetEntity asset) {
        ArtifactAssetPO po = toPO(asset);
        dao.insert(po);
        asset.setId(po.getId());
        return asset;
    }

    @Override
    public AssetEntity queryOwned(String tenantId, String ownerUserId, String assetId) {
        return toEntity(dao.queryOwned(tenantId, ownerUserId, assetId));
    }

    @Override
    public AssetEntity queryReusableByHash(String tenantId, String ownerUserId, String sha256) {
        return toEntity(dao.queryReusableByHash(tenantId, ownerUserId, sha256));
    }

    @Override
    public List<AssetEntity> queryOwnedList(String tenantId, String ownerUserId, Long cursor, int limit,
                                            String sessionId, String assetKind) {
        return dao.queryOwnedList(tenantId, ownerUserId, cursor, limit, sessionId, assetKind)
                .stream().map(this::toEntity).toList();
    }

    @Override
    public int bindReadyAssets(String tenantId, String ownerUserId, String sessionId, String messageId,
                               List<String> assetIds) {
        return dao.bindReadyAssets(tenantId, ownerUserId, sessionId, messageId, assetIds);
    }

    @Override
    public int softDelete(String tenantId, String ownerUserId, String assetId) {
        return dao.softDeleteOwned(tenantId, ownerUserId, assetId);
    }

    @Override
    public List<AssetEntity> queryContextAssets(String tenantId, String ownerUserId, String sessionId,
                                                Integer visibleThroughSequence) {
        return dao.queryContextAssets(tenantId, ownerUserId, sessionId, visibleThroughSequence)
                .stream().map(this::toEntity).toList();
    }

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
