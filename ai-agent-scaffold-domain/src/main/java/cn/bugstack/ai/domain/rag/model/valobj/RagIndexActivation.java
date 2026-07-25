package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 经过索引验证后的文档版本原子激活参数。
 * <p>同时携带知识库 generation、三个聚合 revision、解析统计、不可变解析产物位置、解析器与
 * IR/质量/Tokenizer 版本，仓储只能在全部条件一致时原子切换可见版本。</p>
 */
public record RagIndexActivation(String knowledgeBaseId,
                                 String documentId,
                                 String versionId,
                                 long generation,
                                 long expectedVersionRevision,
                                 long expectedDocumentRevision,
                                 long expectedKnowledgeBaseRevision,
                                 int pageCount,
                                 long characterCount,
                                 int chunkCount,
                                 String parsedObjectBucket,
                                 String parsedObjectKey,
                                 String parsedContentHash,
                                 long parsedSizeBytes,
                                 String parserName,
                                 String parserRevision,
                                 String irSchemaVersion,
                                 String qualityDisposition,
                                 double qualityScore,
                                 String qualityReportObjectKey,
                                 String chunkManifestObjectKey,
                                 String tokenizerVersion) {

    /** 兼容尚未携带结构化预处理元数据的既有调用。 */
    public RagIndexActivation(String knowledgeBaseId, String documentId, String versionId,
                              long generation, long expectedVersionRevision,
                              long expectedDocumentRevision, long expectedKnowledgeBaseRevision,
                              int pageCount, long characterCount, int chunkCount,
                              String parsedObjectBucket, String parsedObjectKey,
                              String parsedContentHash, long parsedSizeBytes) {
        this(knowledgeBaseId, documentId, versionId, generation, expectedVersionRevision,
                expectedDocumentRevision, expectedKnowledgeBaseRevision, pageCount, characterCount,
                chunkCount, parsedObjectBucket, parsedObjectKey, parsedContentHash, parsedSizeBytes,
                null, null, null, null, 0, null, null, null);
    }

    /** 兼容旧调用；只用于不携带解析指标的既有测试或历史任务。 */
    public RagIndexActivation(String knowledgeBaseId, String documentId, String versionId,
                              long generation, long expectedVersionRevision,
                              long expectedDocumentRevision, long expectedKnowledgeBaseRevision) {
        this(knowledgeBaseId, documentId, versionId, generation, expectedVersionRevision,
                expectedDocumentRevision, expectedKnowledgeBaseRevision,
                0, 0L, 0, null, null, null, 0L,
                null, null, null, null, 0, null, null, null);
    }

    public RagIndexActivation {
        requireText(knowledgeBaseId, "knowledgeBaseId");
        requireText(documentId, "documentId");
        requireText(versionId, "versionId");
        if (generation < 1 || expectedVersionRevision < 0 || expectedDocumentRevision < 0
                || expectedKnowledgeBaseRevision < 0 || pageCount < 0 || characterCount < 0
                || chunkCount < 0 || parsedSizeBytes < 0 || !Double.isFinite(qualityScore)
                || qualityScore < 0 || qualityScore > 1) {
            throw new IllegalArgumentException("RAG 索引激活版本参数非法");
        }
        boolean anyParsed = hasText(parsedObjectBucket) || hasText(parsedObjectKey)
                || hasText(parsedContentHash) || parsedSizeBytes > 0;
        if (anyParsed && (!hasText(parsedObjectBucket) || !hasText(parsedObjectKey)
                || parsedContentHash == null || !parsedContentHash.matches("[0-9a-f]{64}")
                || parsedSizeBytes < 1 || characterCount < 1 || chunkCount < 1)) {
            throw new IllegalArgumentException("RAG 解析产物激活参数非法");
        }
        boolean anyPreprocessing = hasText(parserName) || hasText(parserRevision)
                || hasText(irSchemaVersion) || hasText(qualityDisposition)
                || hasText(qualityReportObjectKey) || hasText(chunkManifestObjectKey)
                || hasText(tokenizerVersion) || qualityScore > 0;
        if (anyPreprocessing && (!hasText(parserName) || !hasText(parserRevision)
                || !hasText(irSchemaVersion) || !hasText(qualityDisposition)
                || !qualityDisposition.matches("READY|READY_WITH_WARNING")
                || !hasText(qualityReportObjectKey) || !hasText(chunkManifestObjectKey)
                || !hasText(tokenizerVersion))) {
            throw new IllegalArgumentException("RAG 结构化预处理激活元数据非法");
        }
    }

    /** 校验索引激活所需资源标识与摘要。 */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }

    /** 判断可选解析产物位置是否已填写。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
