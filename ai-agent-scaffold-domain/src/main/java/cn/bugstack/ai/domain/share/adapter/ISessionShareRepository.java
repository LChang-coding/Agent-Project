package cn.bugstack.ai.domain.share.adapter;

import cn.bugstack.ai.domain.share.model.SessionImportEntity;
import cn.bugstack.ai.domain.share.model.SessionShareEntity;

/**
 * 会话分享仓储契约。
 */
public interface ISessionShareRepository {
    int insertShare(SessionShareEntity share);
    SessionShareEntity queryByTokenHash(String tokenHash);
    SessionShareEntity queryOwnerShare(String tenantId, String userId, String shareId);
    SessionShareEntity lockByShareId(String shareId);
    int consumeAccess(String shareId);
    int revoke(String tenantId, String userId, String shareId);
    int revokeBySession(String tenantId, String userId, String sessionId);
    SessionImportEntity queryImport(String shareId, String recipientScopeKey);
    int insertImport(SessionImportEntity sessionImport);
}
