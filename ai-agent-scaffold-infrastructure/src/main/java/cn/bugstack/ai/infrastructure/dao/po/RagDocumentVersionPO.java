package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 文档不可变版本持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagDocumentVersionPO extends BasePO {
    /** 版本所属租户。 */
    private String tenantId;
    /** 版本所属知识库。 */
    private String knowledgeBaseId;
    /** 稳定逻辑文档 ID。 */
    private String documentId;
    /** 不可变文档版本 ID。 */
    private String versionId;
    /** 文档内递增版本号。 */
    private Integer versionNumber;
    /** 本次重建代次，隔离旧 Worker 写入。 */
    private Long generation;
    /** 原文件资产 ID。 */
    private String assetId;
    /** 原文件所在存储桶。 */
    private String sourceBucket;
    /** 原文件对象键。 */
    private String sourceObjectKey;
    /** 上传时文件名。 */
    private String fileName;
    /** 原文件 MIME 类型。 */
    private String mimeType;
    /** 原文件字节数。 */
    private Long sizeBytes;
    /** 原文件内容摘要。 */
    private String contentHash;
    /** 实际解析器名称。 */
    private String parserName;
    /** 实际解析器版本。 */
    private String parserVersion;
    /** 分块算法版本。 */
    private String chunkerVersion;
    /** 写入向量时的 Embedding 模型修订。 */
    private String embeddingModelRevision;
    /** 结构化解析结果存储桶。 */
    private String parsedBucket;
    /** 结构化解析结果对象键。 */
    private String parsedObjectKey;
    /** 解析页数。 */
    private Integer pageCount;
    /** 解析正文字符数。 */
    private Long characterCount;
    /** 最终入库分块数。 */
    private Integer chunkCount;
    /** processing/ready/failed/cancelled/deleted 状态。 */
    private String status;
    /** 解析与索引扩展元数据。 */
    private String metadata;
    /** 行级乐观锁版本。 */
    private Long rowVersion;
    /** 向量与分块全部就绪时间。 */
    private LocalDateTime indexedAt;
}
