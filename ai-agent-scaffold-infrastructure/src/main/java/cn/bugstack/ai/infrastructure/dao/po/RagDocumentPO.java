package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 逻辑文档聚合根及其当前活动/目标索引代次。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagDocumentPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 文档上传者用户ID
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * 知识库业务ID
     */
    private String knowledgeBaseId;

    /**
     * 文档业务ID
     */
    private String documentId;

    /** 原始上传资产 ID。 */
    private String assetId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 来源类型：upload/url/text/oss
     */
    private String sourceType;

    /**
     * 来源地址
     */
    private String sourceUri;

    /** 原文件对象存储桶。 */
    private String sourceBucket;

    /** 原文件对象键。 */
    private String sourceObjectKey;

    /** 原文件 MIME 类型。 */
    private String mimeType;

    /** 原文件字节数。 */
    private Long sizeBytes;

    /**
     * 内容哈希
     */
    private String contentHash;

    /** 最近创建的文档版本号。 */
    private Integer documentVersion;

    /** 当前对检索可见的索引代次。 */
    private Long activeGeneration;

    /** 当前对检索可见的不可变版本 ID。 */
    private String activeVersionId;

    /** 正在构建、尚未激活的目标代次。 */
    private Long targetGeneration;

    /** 最近一次处理使用的解析器。 */
    private String parserName;

    /** 最近一次处理使用的解析器版本。 */
    private String parserVersion;

    /** 活动版本页数。 */
    private Integer pageCount;

    /** 活动版本分块数。 */
    private Integer chunkCount;

    /** 最近失败的稳定错误码。 */
    private String lastErrorCode;

    /** 最近失败的受限错误摘要。 */
    private String lastErrorMessage;

    /** 活动版本完成索引时间。 */
    private java.time.LocalDateTime indexedAt;

    /** 聚合根乐观并发修订号。 */
    private Long revision;

    /**
     * 文档状态：active/indexing/indexed/failed/deleted
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
