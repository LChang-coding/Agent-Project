package cn.bugstack.ai.api.dto.asset;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产响应对象。
 */
@Data
@Builder
public class AssetResponseDTO {
    private String assetId;
    private String assetKind;
    private String assetType;
    private String sessionId;
    private String messageId;
    private String fileName;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private String status;
    private String parseStatus;
    private String parseError;
    private LocalDateTime createTime;
}
