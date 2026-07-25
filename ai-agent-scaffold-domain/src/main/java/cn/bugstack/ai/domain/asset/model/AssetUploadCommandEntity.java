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
    /** 来自认证上下文的租户。 */
    private String tenantId;
    /** 来自认证上下文的用户。 */
    private String ownerUserId;
    /** 可选的预关联会话。 */
    private String sessionId;
    /** 客户端原始文件名。 */
    private String fileName;
    /** 客户端声明 MIME。 */
    private String mimeType;
    /** 受上传上限约束的文件内容。 */
    private byte[] bytes;
}
