package cn.bugstack.ai.domain.share.adapter;

import cn.bugstack.ai.domain.share.model.SessionImportEntity;
import cn.bugstack.ai.domain.share.model.SessionShareEntity;

/**
 * 会话分享仓储契约。
 */
public interface ISessionShareRepository {
    /** 保存新的分享授权。 */
    int insertShare(SessionShareEntity share);
    /** 通过令牌摘要读取分享，数据库不保存原令牌。 */
    SessionShareEntity queryByTokenHash(String tokenHash);
    /** 在创建者身份边界内读取分享。 */
    SessionShareEntity queryOwnerShare(String tenantId, String userId, String shareId);
    /** 锁定分享行，串行化额度消费与幂等导入。 */
    SessionShareEntity lockByShareId(String shareId);
    /** 原子消费一次读取额度，仅活动且未超限时成功。 */
    int consumeAccess(String shareId);
    /** 仅允许创建者撤销分享。 */
    int revoke(String tenantId, String userId, String shareId);
    /** 会话删除前批量撤销其全部分享。 */
    int revokeBySession(String tenantId, String userId, String sessionId);
    /** 查询接收方是否已经导入过该分享。 */
    SessionImportEntity queryImport(String shareId, String recipientScopeKey);
    /** 保存接收方的幂等导入记录。 */
    int insertImport(SessionImportEntity sessionImport);
}
