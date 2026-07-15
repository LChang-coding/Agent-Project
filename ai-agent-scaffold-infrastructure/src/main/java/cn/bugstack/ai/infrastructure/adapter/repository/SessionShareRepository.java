package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.share.adapter.ISessionShareRepository;
import cn.bugstack.ai.domain.share.model.SessionImportEntity;
import cn.bugstack.ai.domain.share.model.SessionShareEntity;
import cn.bugstack.ai.infrastructure.dao.IChatSessionImportDao;
import cn.bugstack.ai.infrastructure.dao.IChatSessionShareDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatSessionImportPO;
import cn.bugstack.ai.infrastructure.dao.po.ChatSessionSharePO;
import org.springframework.stereotype.Repository;

/**
 * 会话分享仓储实现。
 */
@Repository
public class SessionShareRepository implements ISessionShareRepository {

    private final IChatSessionShareDao shareDao;
    private final IChatSessionImportDao importDao;

    public SessionShareRepository(IChatSessionShareDao shareDao, IChatSessionImportDao importDao) {
        this.shareDao = shareDao;
        this.importDao = importDao;
    }

    @Override
    public int insertShare(SessionShareEntity entity) {
        ChatSessionSharePO po = toSharePO(entity);
        int affected = shareDao.insert(po);
        entity.setId(po.getId());
        return affected;
    }

    @Override
    public SessionShareEntity queryByTokenHash(String tokenHash) {
        return toShareEntity(shareDao.queryByTokenHash(tokenHash));
    }

    @Override
    public SessionShareEntity queryOwnerShare(String tenantId, String userId, String shareId) {
        return toShareEntity(shareDao.queryOwnerShare(tenantId, userId, shareId));
    }

    @Override
    public SessionShareEntity lockByShareId(String shareId) {
        return toShareEntity(shareDao.lockByShareId(shareId));
    }

    @Override
    public int consumeAccess(String shareId) {
        return shareDao.consumeAccess(shareId);
    }

    @Override
    public int revoke(String tenantId, String userId, String shareId) {
        return shareDao.revoke(tenantId, userId, shareId);
    }

    @Override
    public int revokeBySession(String tenantId, String userId, String sessionId) {
        return shareDao.revokeBySession(tenantId, userId, sessionId);
    }

    @Override
    public SessionImportEntity queryImport(String shareId, String recipientScopeKey) {
        return toImportEntity(importDao.queryByRecipient(shareId, recipientScopeKey));
    }

    @Override
    public int insertImport(SessionImportEntity entity) {
        ChatSessionImportPO po = toImportPO(entity);
        int affected = importDao.insert(po);
        entity.setId(po.getId());
        return affected;
    }

    private ChatSessionSharePO toSharePO(SessionShareEntity entity) {
        return ChatSessionSharePO.builder()
                .shareId(entity.getShareId()).ownerTenantId(entity.getOwnerTenantId())
                .ownerUserId(entity.getOwnerUserId()).sourceSessionId(entity.getSourceSessionId())
                .tokenHash(entity.getTokenHash()).bucket(entity.getBucket()).objectKey(entity.getObjectKey())
                .schemaVersion(entity.getSchemaVersion()).contentSha256(entity.getContentSha256())
                .sizeBytes(entity.getSizeBytes()).messageCount(entity.getMessageCount()).title(entity.getTitle())
                .status(entity.getStatus()).expiresAt(entity.getExpiresAt()).maxDownloads(entity.getMaxDownloads())
                .downloadCount(entity.getDownloadCount()).revokedAt(entity.getRevokedAt()).build();
    }

    private SessionShareEntity toShareEntity(ChatSessionSharePO po) {
        if (po == null) {
            return null;
        }
        return SessionShareEntity.builder()
                .id(po.getId()).shareId(po.getShareId()).ownerTenantId(po.getOwnerTenantId())
                .ownerUserId(po.getOwnerUserId()).sourceSessionId(po.getSourceSessionId())
                .tokenHash(po.getTokenHash()).bucket(po.getBucket()).objectKey(po.getObjectKey())
                .schemaVersion(po.getSchemaVersion()).contentSha256(po.getContentSha256())
                .sizeBytes(po.getSizeBytes()).messageCount(po.getMessageCount()).title(po.getTitle())
                .status(po.getStatus()).expiresAt(po.getExpiresAt()).maxDownloads(po.getMaxDownloads())
                .downloadCount(po.getDownloadCount()).revokedAt(po.getRevokedAt()).createTime(po.getCreateTime())
                .build();
    }

    private ChatSessionImportPO toImportPO(SessionImportEntity entity) {
        return ChatSessionImportPO.builder().importId(entity.getImportId()).shareId(entity.getShareId())
                .recipientScopeKey(entity.getRecipientScopeKey()).tenantId(entity.getTenantId())
                .userId(entity.getUserId()).sourceSha256(entity.getSourceSha256())
                .newSessionId(entity.getNewSessionId()).status(entity.getStatus()).build();
    }

    private SessionImportEntity toImportEntity(ChatSessionImportPO po) {
        if (po == null) {
            return null;
        }
        return SessionImportEntity.builder().id(po.getId()).importId(po.getImportId()).shareId(po.getShareId())
                .recipientScopeKey(po.getRecipientScopeKey()).tenantId(po.getTenantId()).userId(po.getUserId())
                .sourceSha256(po.getSourceSha256()).newSessionId(po.getNewSessionId()).status(po.getStatus())
                .build();
    }
}
