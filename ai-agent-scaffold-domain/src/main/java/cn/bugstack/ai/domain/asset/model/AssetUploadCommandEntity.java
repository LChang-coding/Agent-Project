package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

/**
 * 聊天附件上传命令。
 * <p>身份字段只能由可信请求上下文填充。</p>
 */
@Data
@Builder
public class AssetUploadCommandEntity {
    private String tenantId;
    private String ownerUserId;
    private String sessionId;
    private String fileName;
    private String mimeType;
    private byte[] bytes;
}
