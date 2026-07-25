package cn.bugstack.ai.domain.share.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话分享授权实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionShareEntity {
    /** 数据库自增主键。 */
    private Long id;
    /** 分享授权业务标识。 */
    private String shareId;
    /** 创建者租户。 */
    private String ownerTenantId;
    /** 创建者用户。 */
    private String ownerUserId;
    /** 生成快照的源会话。 */
    private String sourceSessionId;
    /** 原令牌的 SHA-256，避免数据库泄露后直接访问。 */
    private String tokenHash;
    /** 快照所在对象存储桶。 */
    private String bucket;
    /** 不可变快照对象键。 */
    private String objectKey;
    /** 快照协议版本。 */
    private String schemaVersion;
    /** 快照字节摘要，用于下载前验真。 */
    private String contentSha256;
    /** 快照字节数。 */
    private Long sizeBytes;
    /** 快照包含的消息数。 */
    private Integer messageCount;
    /** 分享展示标题。 */
    private String title;
    /** active 或 revoked。 */
    private String status;
    /** 授权失效时间。 */
    private LocalDateTime expiresAt;
    /** 下载和导入共享的最大读取额度。 */
    private Integer maxDownloads;
    /** 已原子消费的读取次数。 */
    private Integer downloadCount;
    /** 主动撤销时间。 */
    private LocalDateTime revokedAt;
    /** 分享创建时间。 */
    private LocalDateTime createTime;
}
