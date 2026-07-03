package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ArtifactAssetPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 资产拥有者用户ID
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * 关联会话ID
     */
    private String sessionId;

    /**
     * 关联消息ID
     */
    private String messageId;

    /**
     * 资产业务ID
     */
    private String assetId;

    /**
     * 资产类型：image/file/pdf/excel/audio/video
     */
    private String assetType;

    /**
     * 存储桶
     */
    private String bucket;

    /**
     * 对象存储 Key
     */
    private String objectKey;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * MIME 类型
     */
    private String mimeType;

    /**
     * 文件大小，单位字节
     */
    private Long sizeBytes;

    /**
     * 资产状态：active/deleted
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
