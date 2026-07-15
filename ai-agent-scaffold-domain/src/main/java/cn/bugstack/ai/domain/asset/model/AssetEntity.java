package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产领域实体。
 * <p>描述聊天附件的可信归属、对象位置、解析结果和消息引用。</p>
 */
@Data
@Builder
public class AssetEntity {

    private Long id;
    private String tenantId;
    private String ownerUserId;
    private String visibility;
    private String sessionId;
    private String messageId;
    private String assetId;
    private String assetKind;
    private String assetType;
    private String bucket;
    private String objectKey;
    private String fileName;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private String status;
    private String parseStatus;
    private String extractedText;
    private String parseError;
    private String metadata;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
