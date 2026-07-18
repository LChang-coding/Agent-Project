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
    private String tenantId;
    private String knowledgeBaseId;
    private String documentId;
    private String versionId;
    private Integer versionNumber;
    private Long generation;
    private String assetId;
    private String sourceBucket;
    private String sourceObjectKey;
    private String fileName;
    private String mimeType;
    private Long sizeBytes;
    private String contentHash;
    private String parserName;
    private String parserVersion;
    private String chunkerVersion;
    private String embeddingModelRevision;
    private String parsedBucket;
    private String parsedObjectKey;
    private Integer pageCount;
    private Long characterCount;
    private Integer chunkCount;
    private String status;
    private String metadata;
    private Long rowVersion;
    private LocalDateTime indexedAt;
}
