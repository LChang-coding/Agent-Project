package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话分享持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatSessionSharePO extends BasePO {
    /** 分享业务 ID。 */
    private String shareId;
    /** 分享创建者租户。 */
    private String ownerTenantId;
    /** 分享创建者用户。 */
    private String ownerUserId;
    /** 快照来源会话。 */
    private String sourceSessionId;
    /** 下载令牌摘要；数据库不保存明文令牌。 */
    private String tokenHash;
    /** 分享文件所在对象存储桶。 */
    private String bucket;
    /** 分享文件对象键。 */
    private String objectKey;
    /** 导出文件结构版本。 */
    private String schemaVersion;
    /** 分享文件完整性 SHA-256。 */
    private String contentSha256;
    /** 分享文件字节数。 */
    private Long sizeBytes;
    /** 快照内有效消息数。 */
    private Integer messageCount;
    /** 分享展示标题。 */
    private String title;
    /** active/revoked/expired 状态。 */
    private String status;
    /** 分享自然失效时间。 */
    private LocalDateTime expiresAt;
    /** 允许成功消费的最大次数。 */
    private Integer maxDownloads;
    /** 已原子消费的访问次数。 */
    private Integer downloadCount;
    /** 主动撤销时间。 */
    private LocalDateTime revokedAt;
}
