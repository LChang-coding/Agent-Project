package cn.bugstack.ai.domain.rag.model.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可注入模型、可评测且不暴露存储凭证的 RAG 检索结果。
 *
 * @param retrievalId 本次检索的唯一标识
 * @param citations 按最终排名排序的引用上下文
 * @param estimatedTokenCount 全部引用上下文的预估 Token 数
 * @param degraded 本次检索是否使用了降级路径
 * @param degradationReasons 本次检索实际发生的去重降级原因
 * @param metrics 各检索阶段的候选数与耗时
 * @param diagnostics 仅在授权调试请求中返回的有界候选诊断
 */
public record RagRetrievalResult(String retrievalId,
                                 List<Citation> citations,
                                 int estimatedTokenCount,
                                 boolean degraded,
                                 List<String> degradationReasons,
                                 Metrics metrics,
                                 Diagnostics diagnostics) {

    /** 兼容不携带候选诊断的生产结果构造。 */
    public RagRetrievalResult(String retrievalId, List<Citation> citations, int estimatedTokenCount,
                              boolean degraded, List<String> degradationReasons, Metrics metrics) {
        this(retrievalId, citations, estimatedTokenCount, degraded, degradationReasons, metrics,
                Diagnostics.empty());
    }

    /** 校验检索标识、Token 数和阶段指标，并保存列表的防御副本。 */
    public RagRetrievalResult {
        requireText(retrievalId, "检索ID");
        citations = citations == null ? List.of() : List.copyOf(citations);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        diagnostics = diagnostics == null ? Diagnostics.empty() : diagnostics;
        if (estimatedTokenCount < 0 || metrics == null) {
            throw new IllegalArgumentException("RAG检索结果参数非法");
        }
    }

    /**
     * 创建仅含总耗时的空命中结果。
     * @param retrievalId 检索标识
     * @param totalMs 检索主流程总耗时毫秒数
     * @return 不含引用且未降级的检索结果
     */
    public static RagRetrievalResult empty(String retrievalId, long totalMs) {
        return new RagRetrievalResult(retrievalId, List.of(), 0, false, List.of(),
                new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, totalMs));
    }

    /**
     * 创建包含配置解析耗时的空命中结果。
     * @param retrievalId 检索标识
     * @param totalMs 检索主流程总耗时毫秒数
     * @param configurationMs 绑定授权与检索配置解析耗时毫秒数
     * @return 不含引用且带配置耗时的检索结果
     */
    public static RagRetrievalResult empty(String retrievalId, long totalMs, long configurationMs) {
        return new RagRetrievalResult(retrievalId, List.of(), 0, false, List.of(),
                new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, totalMs,
                        configurationMs, 0, 0, 0, 0));
    }

    /**
     * 补齐同步审计和完整服务边界耗时。
     * @param auditMs 同步审计写入耗时毫秒数
     * @param serviceMs 包含审计在内的完整检索服务耗时毫秒数
     * @return 保留检索内容并更新完成耗时的新结果
     */
    public RagRetrievalResult withCompletionTimings(long auditMs, long serviceMs) {
        Metrics value = metrics;
        return new RagRetrievalResult(retrievalId, citations, estimatedTokenCount, degraded, degradationReasons,
                new Metrics(value.denseCandidateCount(), value.sparseCandidateCount(), value.fusionCandidateCount(),
                        value.rerankCandidateCount(), value.embeddingMs(), value.denseMs(), value.sparseMs(),
                        value.fusionMs(), value.rerankMs(), value.totalMs(), value.configurationMs(),
                        value.hydrationMs(), value.assemblyMs(), auditMs, serviceMs), diagnostics);
    }

    /**
     * 标记一次不会丢弃主检索结果的降级；相同原因保持幂等。
     * @param reason 稳定的降级原因
     * @return 已标记降级且保留原引用的新结果
     */
    public RagRetrievalResult withDegradation(String reason) {
        requireText(reason, "降级原因");
        List<String> reasons = new ArrayList<>(degradationReasons);
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
        return new RagRetrievalResult(retrievalId, citations, estimatedTokenCount, true, reasons,
                metrics, diagnostics);
    }

    /**
     * 最终引用；context 可包含同版本父分块和相邻分块，chunkId 始终指向主命中。
     *
     * @param citationId 本次检索内的引用标识
     * @param rank 引用的最终排名
     * @param knowledgeBaseId 知识库标识
     * @param documentId 逻辑文档标识
     * @param documentName 文档展示名称
     * @param versionId 文档版本标识
     * @param documentVersion 文档内递增的版本序号
     * @param generation 命中分块所属索引代际
     * @param chunkId 主命中分块标识
     * @param context 经 Token 预算裁剪后的可引用正文
     * @param pageNumber 主命中所在页码
     * @param headingPath 主命中所在标题路径
     * @param contentHash 主命中正文摘要
     * @param denseScore 稠密召回分数
     * @param sparseScore 稀疏召回分数
     * @param fusionScore 候选融合分数
     * @param rerankScore 重排模型分数
     * @param metadata 不含内部存储位置的引用元数据
     */
    public record Citation(String citationId,
                           int rank,
                           String knowledgeBaseId,
                           String documentId,
                           String documentName,
                           String versionId,
                           int documentVersion,
                           long generation,
                           String chunkId,
                           String context,
                           Integer pageNumber,
                           String headingPath,
                           String contentHash,
                           Double denseScore,
                           Double sparseScore,
                           double fusionScore,
                           Double rerankScore,
                           Map<String, String> metadata) {
        public Citation {
            requireText(citationId, "引用ID");
            requireText(knowledgeBaseId, "知识库ID");
            requireText(documentId, "文档ID");
            requireText(documentName, "文档名");
            requireText(versionId, "版本ID");
            requireText(chunkId, "分块ID");
            requireText(context, "引用正文");
            requireText(contentHash, "内容摘要");
            if (rank < 1 || documentVersion < 1 || generation < 1 || !Double.isFinite(fusionScore)
                    || denseScore != null && !Double.isFinite(denseScore)
                    || sparseScore != null && !Double.isFinite(sparseScore)
                    || rerankScore != null && !Double.isFinite(rerankScore)) {
                throw new IllegalArgumentException("RAG引用参数非法");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /** 不含查询正文的阶段指标，供压测和消融分析使用。 */
    public record Metrics(int denseCandidateCount,
                          int sparseCandidateCount,
                          int fusionCandidateCount,
                          int rerankCandidateCount,
                          long embeddingMs,
                          long denseMs,
                          long sparseMs,
                          long fusionMs,
                          long rerankMs,
                          long totalMs,
                          long configurationMs,
                          long hydrationMs,
                          long assemblyMs,
                          long auditMs,
                          long serviceMs) {
        /** 兼容未拆分配置、水合、组装和审计耗时的旧指标构造。 */
        public Metrics(int denseCandidateCount, int sparseCandidateCount, int fusionCandidateCount,
                       int rerankCandidateCount, long embeddingMs, long denseMs, long sparseMs,
                       long fusionMs, long rerankMs, long totalMs) {
            this(denseCandidateCount, sparseCandidateCount, fusionCandidateCount, rerankCandidateCount,
                    embeddingMs, denseMs, sparseMs, fusionMs, rerankMs, totalMs, 0, 0, 0, 0, 0);
        }

        public Metrics {
            if (denseCandidateCount < 0 || sparseCandidateCount < 0 || fusionCandidateCount < 0
                    || rerankCandidateCount < 0 || embeddingMs < 0 || denseMs < 0 || sparseMs < 0
                    || fusionMs < 0 || rerankMs < 0 || totalMs < 0 || configurationMs < 0
                    || hydrationMs < 0 || assemblyMs < 0 || auditMs < 0 || serviceMs < 0) {
                throw new IllegalArgumentException("RAG检索指标非法");
            }
        }
    }

    /** 仅管理员debug显式开启的有界阶段诊断；不含向量、对象键和正文。 */
    public record Diagnostics(boolean enabled, boolean truncated, int capturedCount,
                              int maxCapturedCount, List<CandidateTrace> candidates) {
        public Diagnostics {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            if (capturedCount < 0 || maxCapturedCount < 0 || capturedCount != candidates.size()
                    || capturedCount > maxCapturedCount || !enabled && (capturedCount > 0 || truncated)) {
                throw new IllegalArgumentException("RAG诊断参数非法");
            }
        }
        /** 返回生产默认关闭的零候选诊断。 */
        public static Diagnostics empty() { return new Diagnostics(false, false, 0, 0, List.of()); }
    }

    /** 单个候选在某一阶段的排名和分数；outcome用于说明保留或淘汰原因。 */
    public record CandidateTrace(String bindingId, String profileId, String stage, int rank,
                                 String knowledgeBaseId, String documentId, String versionId,
                                 long generation, String chunkId, String headingPath,
                                 Double denseScore, Double sparseScore,
                                 Double fusionScore, Double rerankScore, String outcome) {
        public CandidateTrace {
            requireText(bindingId, "绑定ID");
            requireText(profileId, "配置ID");
            requireText(stage, "诊断阶段");
            requireText(knowledgeBaseId, "知识库ID");
            requireText(documentId, "文档ID");
            requireText(versionId, "版本ID");
            requireText(chunkId, "分块ID");
            requireText(outcome, "候选结果");
            if (rank < 1 || generation < 1
                    || denseScore != null && !Double.isFinite(denseScore)
                    || sparseScore != null && !Double.isFinite(sparseScore)
                    || fusionScore != null && !Double.isFinite(fusionScore)
                    || rerankScore != null && !Double.isFinite(rerankScore)) {
                throw new IllegalArgumentException("RAG候选诊断参数非法");
            }
        }
    }

    /** 校验检索结果中的必填公开标识。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
