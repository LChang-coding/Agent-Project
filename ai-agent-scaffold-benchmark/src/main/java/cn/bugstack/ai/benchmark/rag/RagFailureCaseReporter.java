package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从完整质量run中确定性生成召回失败案例和证据说明。 */
public final class RagFailureCaseReporter {

    static final List<String> VARIANTS = List.of("dense", "sparse", "hybrid_rrf", "hybrid_rrf_rerank");
    private static final Pattern HEADING = Pattern.compile("^# ([^ ]+) — (.+)$");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final int EXCERPT_LIMIT = 600;

    private final ObjectMapper mapper;
    private final RagRetrievalScorer scorer = new RagRetrievalScorer();

    public RagFailureCaseReporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Report generate(Configuration configuration) throws IOException {
        configuration.validate();
        Map<String, String> queries = readQueries(configuration.queries());
        Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(mapper)
                .loadQrels(configuration.qrels(), BeirDatasetLoader.Limits.defaults());
        Map<String, DocumentEvidence> documents = readDocuments(configuration.documents(), configuration.documentMap());
        Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> runs = readAndValidateRun(configuration.run(), qrels);

        Map<String, List<CaseEvidence>> all = new LinkedHashMap<>();
        categories().forEach(category -> all.put(category, new ArrayList<>()));
        qrels.keySet().stream().sorted().forEach(queryId -> classify(queryId, queries.get(queryId), qrels.get(queryId),
                runs.get(queryId), documents, all));

        Map<String, Integer> available = new LinkedHashMap<>();
        Map<String, List<CaseEvidence>> selected = new LinkedHashMap<>();
        all.forEach((category, values) -> {
            values.sort(Comparator.comparingDouble(CaseEvidence::selectionMagnitude).reversed()
                    .thenComparing(CaseEvidence::queryId));
            available.put(category, values.size());
            selected.put(category, List.copyOf(values.subList(0, Math.min(configuration.maxPerCategory(), values.size()))));
        });
        Map<String, String> inputHashes = new LinkedHashMap<>();
        inputHashes.put("queries", sha256(configuration.queries()));
        inputHashes.put("qrels", sha256(configuration.qrels()));
        inputHashes.put("documents", sha256(configuration.documents()));
        inputHashes.put("documentMap", sha256(configuration.documentMap()));
        inputHashes.put("run", sha256(configuration.run()));
        Manifest manifest = new Manifest(1, "rag-failure-case-v1", qrels.size(),
                Files.readAllLines(configuration.run(), StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).count(),
                configuration.maxPerCategory(), available, inputHashes,
                List.of("run只保存最终Top10文档ID，没有逐候选分数或Dense/Sparse内部候选ID；对应字段明确标记为未采集。",
                        "首个失败步骤是基于四个消融终态排名的首个可观测步骤，不等同于内部算子级因果证明。",
                        "词项重合只用于提出可证伪推断，不作为失败原因的直接证明。"));
        return new Report(manifest, selected);
    }

    public void write(Report report, Path json, Path markdown) throws IOException {
        if (Files.exists(json) || Files.exists(markdown)) throw new IllegalArgumentException("禁止覆盖既有失败案例报告");
        if (json.getParent() != null) Files.createDirectories(json.getParent());
        if (markdown.getParent() != null) Files.createDirectories(markdown.getParent());
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), report);
        Files.writeString(markdown, renderMarkdown(report), StandardCharsets.UTF_8);
    }

    private Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> readAndValidateRun(
            Path path, Map<String, Map<String, Integer>> qrels) throws IOException {
        List<RagBenchmarkRunIO.RunRecord> records = new RagBenchmarkRunIO(mapper).readRecords(path);
        Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> result = new LinkedHashMap<>();
        for (RagBenchmarkRunIO.RunRecord record : records) {
            if (!VARIANTS.contains(record.variant())) throw new IllegalArgumentException("run包含未知变体: " + record.variant());
            if (!qrels.containsKey(record.queryId())) throw new IllegalArgumentException("run包含未知查询: " + record.queryId());
            if (record.degraded() || record.errorCode() != null || record.rankedDocumentIds().isEmpty()) {
                throw new IllegalArgumentException("run包含错误、降级或空结果: " + record.variant() + "/" + record.queryId());
            }
            Map<String, RagBenchmarkRunIO.RunRecord> byVariant = result.computeIfAbsent(record.queryId(), ignored -> new LinkedHashMap<>());
            if (byVariant.putIfAbsent(record.variant(), record) != null) {
                throw new IllegalArgumentException("run包含重复variant/query: " + record.variant() + "/" + record.queryId());
            }
        }
        for (String queryId : qrels.keySet()) {
            Map<String, RagBenchmarkRunIO.RunRecord> values = result.get(queryId);
            if (values == null || !values.keySet().equals(new LinkedHashSet<>(VARIANTS))) {
                throw new IllegalArgumentException("查询缺少完整四变体: " + queryId);
            }
        }
        if (records.size() != qrels.size() * VARIANTS.size()) throw new IllegalArgumentException("run记录数不是query×4");
        return result;
    }

    private void classify(String queryId, String question, Map<String, Integer> relevance,
                          Map<String, RagBenchmarkRunIO.RunRecord> runs, Map<String, DocumentEvidence> documents,
                          Map<String, List<CaseEvidence>> output) {
        Map<String, VariantEvidence> variants = new LinkedHashMap<>();
        VARIANTS.forEach(name -> variants.put(name, variant(name, relevance, runs.get(name), documents)));
        boolean dense = variants.get("dense").metrics().successAt10() > 0;
        boolean sparse = variants.get("sparse").metrics().successAt10() > 0;
        boolean hybrid = variants.get("hybrid_rrf").metrics().successAt10() > 0;
        boolean rerank = variants.get("hybrid_rrf_rerank").metrics().successAt10() > 0;
        List<String> categories = new ArrayList<>();
        if (!dense && hybrid) categories.add("dense_miss_hybrid_hit");
        if (!sparse && hybrid) categories.add("sparse_miss_hybrid_hit");
        if (!hybrid && rerank) categories.add("rerank_rescue");
        if (hybrid && !rerank) categories.add("rerank_harm");
        if (dense && !sparse) categories.add("dense_only_success");
        if (sparse && !dense) categories.add("sparse_only_success");
        if (!dense && !sparse && !hybrid && !rerank) categories.add("persistent_miss");
        double hybridMrr = variants.get("hybrid_rrf").metrics().mrrAt10();
        double rerankMrr = variants.get("hybrid_rrf_rerank").metrics().mrrAt10();
        if (hybrid && rerank && rerankMrr > hybridMrr) categories.add("rerank_reorder_gain");
        if (hybrid && rerank && rerankMrr < hybridMrr) categories.add("rerank_reorder_harm");
        for (String category : categories) {
            List<DocumentEvidence> gold = relevance.entrySet().stream().filter(entry -> entry.getValue() > 0)
                    .map(entry -> requireDocument(documents, entry.getKey())).toList();
            double magnitude = magnitude(category, variants);
            output.get(category).add(new CaseEvidence(category, queryId, question, gold, variants,
                    firstObservableFailure(category), directFacts(category, variants), inference(question, gold, variants),
                    alternativeExplanation(category), falsification(category), magnitude));
        }
    }

    private VariantEvidence variant(String name, Map<String, Integer> qrels, RagBenchmarkRunIO.RunRecord record,
                                    Map<String, DocumentEvidence> documents) {
        RagRetrievalScorer.QueryMetrics metrics = scorer.scoreQuery(record.queryId(), qrels, record.rankedDocumentIds());
        List<RankedDocument> ranking = new ArrayList<>();
        for (int index = 0; index < record.rankedDocumentIds().size(); index++) {
            String documentId = record.rankedDocumentIds().get(index);
            DocumentEvidence document = requireDocument(documents, documentId);
            ranking.add(new RankedDocument(index + 1, documentId, document.title(), document.excerpt(),
                    document.headingMarker(), document.sourcePaths(), qrels.getOrDefault(documentId, 0), null));
        }
        return new VariantEvidence(name, metrics, record.elapsedMs(), sorted(record.stageTimingsMs()),
                sorted(record.candidateCounts()), List.copyOf(ranking), "not_captured_in_run_jsonl");
    }

    private Map<String, String> readQueries(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                String id = node.path("queryId").asText();
                String text = node.path("text").asText();
                if (id.isBlank() || text.isBlank() || result.putIfAbsent(id, text) != null) {
                    throw new IllegalArgumentException("queries记录非法或重复");
                }
            }
        }
        return result;
    }

    private Map<String, DocumentEvidence> readDocuments(Path markdown, Path mapPath) throws IOException {
        Map<String, DocumentMapEntry> markerToEntry = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(mapPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                String marker = node.path("headingMarker").asText();
                String id = node.path("documentId").asText();
                Map<String, String> sourcePaths = new LinkedHashMap<>();
                node.path("sourcePaths").fields().forEachRemaining(entry ->
                        sourcePaths.put(entry.getKey(), entry.getValue().asText()));
                if (marker.isBlank() || id.isBlank()
                        || markerToEntry.putIfAbsent(marker, new DocumentMapEntry(id, Map.copyOf(sourcePaths))) != null) {
                    throw new IllegalArgumentException("document-map记录非法或重复");
                }
            }
        }
        Map<String, DocumentEvidence> result = new LinkedHashMap<>();
        String marker = null;
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : Files.readAllLines(markdown, StandardCharsets.UTF_8)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                flushDocument(result, markerToEntry, marker, title, body.toString());
                marker = matcher.group(1);
                title = matcher.group(2).strip();
                body.setLength(0);
            } else if (marker != null) {
                if (!body.isEmpty()) body.append('\n');
                body.append(line);
            }
        }
        flushDocument(result, markerToEntry, marker, title, body.toString());
        if (result.size() != markerToEntry.size()) throw new IllegalArgumentException("Markdown文档数与document-map不一致");
        return result;
    }

    private void flushDocument(Map<String, DocumentEvidence> result, Map<String, DocumentMapEntry> markerToEntry,
                               String marker, String title, String content) {
        if (marker == null) return;
        DocumentMapEntry entry = markerToEntry.get(marker);
        String id = entry == null ? null : entry.documentId();
        if (id == null || content == null || content.isBlank()) throw new IllegalArgumentException("Markdown包含未映射或空文档: " + marker);
        String normalized = content.strip();
        String excerpt = normalized.length() <= EXCERPT_LIMIT ? normalized : normalized.substring(0, EXCERPT_LIMIT).stripTrailing() + "…";
        if (result.putIfAbsent(id, new DocumentEvidence(id, title, excerpt, marker, entry.sourcePaths())) != null) {
            throw new IllegalArgumentException("Markdown文档ID重复: " + id);
        }
    }

    private DocumentEvidence requireDocument(Map<String, DocumentEvidence> documents, String id) {
        DocumentEvidence value = documents.get(id);
        if (value == null) throw new IllegalArgumentException("排名/qrels引用未固化文档: " + id);
        return value;
    }

    private String inference(String question, List<DocumentEvidence> gold, Map<String, VariantEvidence> variants) {
        double goldOverlap = gold.stream().mapToDouble(doc -> lexicalOverlap(question, doc.title() + " " + doc.excerpt())).max().orElse(0D);
        boolean denseHit = variants.get("dense").metrics().successAt10() > 0;
        boolean sparseHit = variants.get("sparse").metrics().successAt10() > 0;
        if (!sparseHit && denseHit) return String.format(Locale.ROOT,
                "推断：稀疏词项匹配对同义改写或词形差异不敏感；query与gold标题/片段最大词项Jaccard=%.4f。", goldOverlap);
        if (!denseHit && sparseHit) return String.format(Locale.ROOT,
                "推断：Dense语义向量把主题相近干扰文档排在gold之前，而精确术语帮助Sparse命中；最大词项Jaccard=%.4f。", goldOverlap);
        return String.format(Locale.ROOT,
                "推断：Top10截断、语义近邻竞争或Rerank排序偏好可能共同影响；query与gold最大词项Jaccard=%.4f。", goldOverlap);
    }

    private double lexicalOverlap(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) return 0D;
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return intersection.size() / (double) union.size();
    }

    private Set<String> tokens(String value) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) if (matcher.group().length() > 1) values.add(matcher.group());
        return values;
    }

    private List<String> directFacts(String category, Map<String, VariantEvidence> variants) {
        VariantEvidence dense = variants.get("dense");
        VariantEvidence sparse = variants.get("sparse");
        VariantEvidence hybrid = variants.get("hybrid_rrf");
        VariantEvidence rerank = variants.get("hybrid_rrf_rerank");
        return List.of("Dense gold首名次=" + firstGoldRank(dense), "Sparse gold首名次=" + firstGoldRank(sparse),
                "Hybrid-RRF gold首名次=" + firstGoldRank(hybrid), "Hybrid-RRF+Rerank gold首名次=" + firstGoldRank(rerank),
                "分类规则=" + category, "逐候选分数=未采集");
    }

    private String firstGoldRank(VariantEvidence variant) {
        return variant.ranking().stream().filter(value -> value.relevance() > 0).map(value -> String.valueOf(value.rank()))
                .findFirst().orElse("Top10未命中");
    }

    private double magnitude(String category, Map<String, VariantEvidence> variants) {
        VariantEvidence dense = variants.get("dense");
        VariantEvidence sparse = variants.get("sparse");
        VariantEvidence hybrid = variants.get("hybrid_rrf");
        VariantEvidence rerank = variants.get("hybrid_rrf_rerank");
        return switch (category) {
            case "dense_miss_hybrid_hit" -> hybrid.metrics().mrrAt10() - dense.metrics().mrrAt10();
            case "sparse_miss_hybrid_hit" -> hybrid.metrics().mrrAt10() - sparse.metrics().mrrAt10();
            case "rerank_rescue", "rerank_reorder_gain" -> rerank.metrics().mrrAt10() - hybrid.metrics().mrrAt10();
            case "rerank_harm", "rerank_reorder_harm" -> hybrid.metrics().mrrAt10() - rerank.metrics().mrrAt10();
            case "dense_only_success" -> dense.metrics().mrrAt10();
            case "sparse_only_success" -> sparse.metrics().mrrAt10();
            default -> 1D;
        };
    }

    private String firstObservableFailure(String category) {
        return switch (category) {
            case "dense_miss_hybrid_hit" -> "dense_final_top10";
            case "sparse_miss_hybrid_hit" -> "sparse_final_top10";
            case "rerank_rescue" -> "hybrid_rrf_final_top10_before_rerank";
            case "rerank_harm", "rerank_reorder_harm" -> "rerank_final_top10";
            case "rerank_reorder_gain" -> "hybrid_rrf_rank_order";
            case "dense_only_success" -> "sparse_final_top10";
            case "sparse_only_success" -> "dense_final_top10";
            default -> "dense_and_sparse_final_top10";
        };
    }

    private String alternativeExplanation(String category) {
        return "其他可能解释：相关性标注不完整、gold摘要与claim粒度不同、分块边界或Top10截断；当前终态run不能排除这些因素（" + category + "）。";
    }

    private String falsification(String category) {
        return "反证实验：固定同一索引和query，额外留存Dense/Sparse候选Top100、融合逐项贡献、Rerank输入输出分数及chunk文本，再复跑该query验证首个内部失效点（" + category + "）。";
    }

    private List<String> categories() {
        return List.of("dense_miss_hybrid_hit", "sparse_miss_hybrid_hit", "rerank_rescue", "rerank_harm",
                "dense_only_success", "sparse_only_success", "persistent_miss", "rerank_reorder_gain", "rerank_reorder_harm");
    }

    private String renderMarkdown(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# RAG召回失败案例可复算报告\n\n");
        out.append("生成器：").append(report.manifest().generator()).append("；查询数：")
                .append(report.manifest().queryCount()).append("；run记录数：").append(report.manifest().runRecordCount()).append("。\n\n");
        out.append("## 证据边界\n\n");
        report.manifest().limitations().forEach(value -> out.append("- ").append(value).append("\n"));
        out.append("\n## 分类总账\n\n| 类别 | 全量案例数 | 展示数 |\n|---|---:|---:|\n");
        report.cases().forEach((category, values) -> out.append("| ").append(category).append(" | ")
                .append(report.manifest().availableCaseCounts().get(category)).append(" | ").append(values.size()).append(" |\n"));
        for (Map.Entry<String, List<CaseEvidence>> entry : report.cases().entrySet()) {
            out.append("\n## ").append(entry.getKey()).append("\n");
            if (entry.getValue().isEmpty()) out.append("\n当前完整run中无符合该确定性规则的案例。\n");
            for (CaseEvidence value : entry.getValue()) renderCase(out, value);
        }
        out.append("\n## 输入SHA-256\n\n");
        report.manifest().inputSha256().forEach((name, hash) -> out.append("- ").append(name).append(": `").append(hash).append("`\n"));
        return out.toString();
    }

    private void renderCase(StringBuilder out, CaseEvidence value) {
        out.append("\n### queryId=").append(value.queryId()).append("\n\n");
        out.append("问题：").append(value.question()).append("\n\nGold文档：\n\n");
        for (DocumentEvidence doc : value.goldDocuments()) {
            out.append("- `").append(doc.documentId()).append("` ").append(doc.title()).append("\n\n  > ")
                    .append(doc.excerpt().replace("\n", " ")).append("\n");
            renderSourcePaths(out, doc.sourcePaths(), "  ");
        }
        out.append("\n| 变体 | Recall@10 | MRR@10 | nDCG@10 | Gold首名次 | 延迟ms | Top10文档ID（*为gold） |\n")
                .append("|---|---:|---:|---:|---:|---:|---|\n");
        value.variants().forEach((name, variant) -> out.append("| ").append(name).append(" | ")
                .append(format(variant.metrics().recallAt10())).append(" | ").append(format(variant.metrics().mrrAt10()))
                .append(" | ").append(format(variant.metrics().ndcgAt10())).append(" | ").append(firstGoldRank(variant))
                .append(" | ").append(variant.elapsedMs()).append(" | ")
                .append(variant.ranking().stream().map(item -> item.documentId() + (item.relevance() > 0 ? "*" : ""))
                        .reduce((a, b) -> a + ", " + b).orElse(""))
                .append(" |\n"));
        VariantEvidence failedVariant = value.variants().get(failureVariant(value.category()));
        out.append("\n首个失败步骤中的关键错误召回文档（前3条非gold）：\n\n");
        failedVariant.ranking().stream().filter(item -> item.relevance() < 1).limit(3).forEach(item -> out
                .append("- rank=").append(item.rank()).append(" `").append(item.documentId()).append("` ")
                .append(item.title()).append("（本地heading=`").append(item.headingMarker()).append("`）\n\n  > ")
                .append(item.excerpt().replace("\n", " ")).append("\n")
                .append(renderSourcePaths(item.sourcePaths(), "  ")));
        out.append("\n首个可观测失败步骤：`").append(value.firstObservableFailure()).append("`。\n\n直接证据：\n\n");
        value.directFacts().forEach(fact -> out.append("- ").append(fact).append("\n"));
        out.append("\n").append(value.inference()).append("\n\n").append(value.alternativeExplanation())
                .append("\n\n").append(value.falsification()).append("\n");
    }

    private void renderSourcePaths(StringBuilder out, Map<String, String> sourcePaths, String indent) {
        out.append(renderSourcePaths(sourcePaths, indent));
    }

    private String renderSourcePaths(Map<String, String> sourcePaths, String indent) {
        if (sourcePaths == null || sourcePaths.isEmpty()) return "";
        StringBuilder rendered = new StringBuilder();
        rendered.append("\n").append(indent).append("本地源文件：");
        sourcePaths.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                rendered.append(" `").append(entry.getKey()).append("=")
                        .append(entry.getValue()).append("`"));
        rendered.append("\n");
        return rendered.toString();
    }

    private String format(double value) { return String.format(Locale.ROOT, "%.6f", value); }

    private <T> Map<String, T> sorted(Map<String, T> values) {
        Map<String, T> sorted = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return java.util.Collections.unmodifiableMap(sorted);
    }

    private String failureVariant(String category) {
        return switch (category) {
            case "dense_miss_hybrid_hit", "sparse_only_success", "persistent_miss" -> "dense";
            case "sparse_miss_hybrid_hit", "dense_only_success" -> "sparse";
            case "rerank_rescue", "rerank_reorder_gain" -> "hybrid_rrf";
            default -> "hybrid_rrf_rerank";
        };
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    public record Configuration(Path queries, Path qrels, Path documents, Path documentMap, Path run,
                                int maxPerCategory) {
        void validate() {
            if (maxPerCategory < 1 || maxPerCategory > 100) throw new IllegalArgumentException("每类案例数必须为1-100");
            for (Path path : List.of(queries, qrels, documents, documentMap, run)) {
                if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                    throw new IllegalArgumentException("失败案例输入不存在或不可读: " + path);
                }
            }
        }
    }

    public record Report(Manifest manifest, Map<String, List<CaseEvidence>> cases) {}
    public record Manifest(int schemaVersion, String generator, int queryCount, long runRecordCount,
                           int maxPerCategory, Map<String, Integer> availableCaseCounts,
                           Map<String, String> inputSha256, List<String> limitations) {}
    public record CaseEvidence(String category, String queryId, String question,
                               List<DocumentEvidence> goldDocuments, Map<String, VariantEvidence> variants,
                               String firstObservableFailure, List<String> directFacts, String inference,
                               String alternativeExplanation, String falsification, double selectionMagnitude) {}
    public record DocumentEvidence(String documentId, String title, String excerpt, String headingMarker,
                                   Map<String, String> sourcePaths) {}
    public record VariantEvidence(String variant, RagRetrievalScorer.QueryMetrics metrics, long elapsedMs,
                                  Map<String, Long> stageTimingsMs, Map<String, Integer> candidateCounts,
                                  List<RankedDocument> ranking, String scoreEvidence) {}
    public record RankedDocument(int rank, String documentId, String title, String excerpt,
                                 String headingMarker, Map<String, String> sourcePaths,
                                 int relevance, Double score) {}
    private record DocumentMapEntry(String documentId, Map<String, String> sourcePaths) {}
}
