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
    private String shareId;
    private String ownerTenantId;
    private String ownerUserId;
    private String sourceSessionId;
    private String tokenHash;
    private String bucket;
    private String objectKey;
    private String schemaVersion;
    private String contentSha256;
    private Long sizeBytes;
    private Integer messageCount;
    private String title;
    private String status;
    private LocalDateTime expiresAt;
    private Integer maxDownloads;
    private Integer downloadCount;
    private LocalDateTime revokedAt;
}
