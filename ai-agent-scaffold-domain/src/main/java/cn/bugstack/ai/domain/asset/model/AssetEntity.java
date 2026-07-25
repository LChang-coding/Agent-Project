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

    /** 数据库内部主键。 */
    private Long id;
    /** 资产所属租户；个人模式可为空。 */
    private String tenantId;
    /** 资产唯一所有者。 */
    private String ownerUserId;
    /** 可见范围，聊天附件固定为 private。 */
    private String visibility;
    /** 上传时声明或消息绑定后的会话。 */
    private String sessionId;
    /** 原子绑定后的用户消息。 */
    private String messageId;
    /** 对外资产标识。 */
    private String assetId;
    /** 资产用途分类。 */
    private String assetKind;
    /** image、pdf、word、text 或 file。 */
    private String assetType;
    /** 原文件所在对象存储桶。 */
    private String bucket;
    /** 原文件对象键。 */
    private String objectKey;
    /** 清理路径语义后的展示文件名。 */
    private String fileName;
    /** 上传内容类型。 */
    private String mimeType;
    /** 原文件字节数。 */
    private Long sizeBytes;
    /** 内容 SHA-256，用于同用户去重。 */
    private String sha256;
    /** 资产生命周期状态。 */
    private String status;
    /** 文本解析生命周期状态。 */
    private String parseStatus;
    /** 受限提取的上下文文本。 */
    private String extractedText;
    /** 不含敏感正文的解析错误摘要。 */
    private String parseError;
    /** 扩展元数据 JSON。 */
    private String metadata;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    private LocalDateTime updateTime;
}
