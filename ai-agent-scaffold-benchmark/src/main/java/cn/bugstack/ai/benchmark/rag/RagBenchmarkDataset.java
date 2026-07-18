package cn.bugstack.ai.benchmark.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 内存中的 BEIR 风格语料、查询和相关性标注。 */
public record RagBenchmarkDataset(Map<String, Document> documents,
                                  Map<String, String> queries,
                                  Map<String, Map<String, Integer>> qrels) {

    public RagBenchmarkDataset {
        documents = Map.copyOf(documents == null ? Map.of() : documents);
        queries = Map.copyOf(queries == null ? Map.of() : queries);
        Map<String, Map<String, Integer>> immutableQrels = new LinkedHashMap<>();
        if (qrels != null) {
            qrels.forEach((queryId, values) -> immutableQrels.put(queryId, Map.copyOf(values)));
        }
        qrels = Map.copyOf(immutableQrels);
        validate(documents, queries, qrels);
    }

    /**
     * 生成可重复的 positive-closed 子集：先固定查询，再保留其全部正例，最后以稳定哈希补足干扰文档。
     * 该子集只能以实际规模报告，不能外推成全量数据集结果。
     */
    public RagBenchmarkDataset deterministicSubset(int maxDocuments, int maxQueries, long seed) {
        if (maxDocuments < 1 || maxQueries < 1) {
            throw new IllegalArgumentException("子集文档数和查询数必须为正数");
        }
        List<String> queryCandidates = new ArrayList<>(qrels.keySet());
        queryCandidates.sort(stableOrder(seed));
        Set<String> selectedQueries = new LinkedHashSet<>();
        Set<String> requiredDocuments = new LinkedHashSet<>();
        for (String queryId : queryCandidates) {
            Set<String> positives = positiveDocuments(qrels.get(queryId));
            Set<String> union = new LinkedHashSet<>(requiredDocuments);
            union.addAll(positives);
            if (union.size() <= maxDocuments) {
                selectedQueries.add(queryId);
                requiredDocuments.addAll(positives);
            }
            if (selectedQueries.size() == maxQueries) break;
        }
        if (selectedQueries.isEmpty()) {
            throw new IllegalArgumentException("文档上限不足以容纳任一查询的全部正例");
        }
        List<String> distractors = documents.keySet().stream()
                .filter(id -> !requiredDocuments.contains(id)).sorted(stableOrder(seed ^ 0x9E3779B97F4A7C15L))
                .limit(maxDocuments - requiredDocuments.size()).toList();
        requiredDocuments.addAll(distractors);

        Map<String, Document> subsetDocuments = new LinkedHashMap<>();
        requiredDocuments.stream().sorted().forEach(id -> subsetDocuments.put(id, documents.get(id)));
        Map<String, String> subsetQueries = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> subsetQrels = new LinkedHashMap<>();
        selectedQueries.stream().sorted().forEach(id -> {
            subsetQueries.put(id, queries.get(id));
            Map<String, Integer> filtered = new LinkedHashMap<>();
            qrels.get(id).forEach((documentId, relevance) -> {
                if (requiredDocuments.contains(documentId)) filtered.put(documentId, relevance);
            });
            subsetQrels.put(id, filtered);
        });
        return new RagBenchmarkDataset(subsetDocuments, subsetQueries, subsetQrels);
    }

    private static Comparator<String> stableOrder(long seed) {
        return Comparator.comparing((String value) -> sha256(seed + "\0" + value)).thenComparing(value -> value);
    }

    private static Set<String> positiveDocuments(Map<String, Integer> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach((documentId, relevance) -> {
            if (relevance > 0) result.add(documentId);
        });
        return result;
    }

    private static void validate(Map<String, Document> documents, Map<String, String> queries,
                                 Map<String, Map<String, Integer>> qrels) {
        if (documents.isEmpty() || queries.isEmpty() || qrels.isEmpty()) {
            throw new IllegalArgumentException("基准语料、查询和qrels均不能为空");
        }
        for (Map.Entry<String, Map<String, Integer>> entry : qrels.entrySet()) {
            if (!queries.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("qrels引用了不存在的查询: " + entry.getKey());
            }
            boolean positive = false;
            for (Map.Entry<String, Integer> relevance : entry.getValue().entrySet()) {
                if (!documents.containsKey(relevance.getKey())) {
                    throw new IllegalArgumentException("qrels引用了不存在的文档: " + relevance.getKey());
                }
                if (relevance.getValue() < 0) throw new IllegalArgumentException("相关性分数不能为负数");
                positive |= relevance.getValue() > 0;
            }
            if (!positive) throw new IllegalArgumentException("每个评测查询至少需要一个正例: " + entry.getKey());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    public record Document(String id, String title, String text) {
        public Document {
            if (id == null || id.isBlank() || text == null || text.isBlank()) {
                throw new IllegalArgumentException("基准文档ID和正文不能为空");
            }
            title = title == null ? "" : title.strip();
            text = text.strip();
        }
    }
}
