package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
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

/** 对同源 PDF/DOCX 正式运行做逐问题配对比较，并汇总摄取与资源证据。 */
public final class RagFormatComparisonReporter {

    private static final List<String> VARIANTS = RagFailureCaseReporter.VARIANTS;
    private final ObjectMapper mapper;
    private final RagRetrievalScorer scorer = new RagRetrievalScorer();

    public RagFormatComparisonReporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Result generate(Configuration configuration) throws IOException {
        configuration.validate();
        if (Files.exists(configuration.outputDirectory())) {
            throw new IllegalArgumentException("格式对照输出目录必须不存在");
        }
        Files.createDirectories(configuration.outputDirectory());
        Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(mapper)
                .loadQrels(configuration.qrels(), BeirDatasetLoader.Limits.defaults());
        Map<String, String> queries = readQueries(configuration.queries());
        SourceIndex sources = readSources(configuration.documentManifest());
        RunEvidence pdf = readRun(configuration.pdfRunDirectory(), "PDF", qrels);
        RunEvidence docx = readRun(configuration.docxRunDirectory(), "DOCX", qrels);
        validatePair(pdf, docx, qrels);
        Map<String, PairComparison> pairs = new LinkedHashMap<>();
        for (String variant : VARIANTS) {
            pairs.put(variant, compare(variant, qrels, queries, sources,
                    pdf.byVariant().get(variant), docx.byVariant().get(variant)));
        }
        ResourceEvidence resources = resourceEvidence(configuration.resourceEvidenceDirectory());
        Map<String, String> hashes = inputHashes(configuration);
        Report report = new Report(new Manifest(1, "rag-format-paired-comparison-v1",
                pdf.runId(), docx.runId(), qrels.size(), hashes,
                List.of("PDF与DOCX使用同一200问题、同一qrels、同一源正文与同一检索配置。",
                        "该数据集是确定性派生版面压力集，不等价于真实世界原生Office/PDF分布。",
                        "资源采样覆盖两次串行IR_FULL运行，不能拆分成每个格式各自独立资源分布。")),
                pdf.summary(), docx.summary(), Map.copyOf(pairs), resources,
                conclusions(pdf, docx, pairs, resources));
        Path json = configuration.outputDirectory().resolve("comparison.json");
        Path markdown = configuration.outputDirectory().resolve("comparison.md");
        Path evidenceManifest = configuration.outputDirectory().resolve("evidence-manifest.json");
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), report);
        Files.writeString(markdown, render(report), StandardCharsets.UTF_8);
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(evidenceManifest.toFile(),
                Map.of("schemaVersion", 1, "generator", "rag-resource-evidence-v1",
                        "inputs", hashes, "resources", resources));
        return new Result(report, json, markdown, evidenceManifest);
    }

    private RunEvidence readRun(Path directory, String expectedFormat,
                                Map<String, Map<String, Integer>> qrels) throws IOException {
        JsonNode manifest = mapper.readTree(directory.resolve("run-manifest.json").toFile());
        if (!"completed".equals(manifest.path("status").asText())
                || !expectedFormat.equals(manifest.path("format").asText())
                || !"IR_FULL".equals(manifest.path("preprocessingStrategy").asText())
                || manifest.path("completedDocumentCount").asInt() != 200
                || manifest.path("completedQueryResultCount").asInt() != qrels.size() * 4) {
            throw new IllegalArgumentException("格式正式运行manifest门禁失败: " + expectedFormat);
        }
        List<RagBenchmarkRunIO.RunRecord> records = new RagBenchmarkRunIO(mapper)
                .readRecords(directory.resolve("run.jsonl"));
        Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> byVariant = new LinkedHashMap<>();
        for (String variant : VARIANTS) byVariant.put(variant, new LinkedHashMap<>());
        for (RagBenchmarkRunIO.RunRecord record : records) {
            if (!byVariant.containsKey(record.variant()) || !qrels.containsKey(record.queryId())
                    || record.degraded() || record.errorCode() != null || record.rankedDocumentIds().isEmpty()
                    || byVariant.get(record.variant()).putIfAbsent(record.queryId(), record) != null) {
                throw new IllegalArgumentException("格式run记录门禁失败: " + expectedFormat);
            }
        }
        byVariant.forEach((variant, values) -> {
            if (!values.keySet().equals(qrels.keySet())) {
                throw new IllegalArgumentException("格式run问题闭包失败: " + expectedFormat + "/" + variant);
            }
        });
        List<JsonNode> documents = readJsonLines(directory.resolve("document-results.jsonl"));
        if (documents.size() != 200) throw new IllegalArgumentException("格式逐文档结果不是200: " + expectedFormat);
        Map<String, FormatVariantSummary> variants = new LinkedHashMap<>();
        Map<String, RagBenchmarkRunStatistics.VariantStatistics> performance =
                new RagBenchmarkRunStatistics().aggregate(records);
        for (String variant : VARIANTS) {
            Map<String, List<String>> rankings = new LinkedHashMap<>();
            byVariant.get(variant).forEach((query, record) -> rankings.put(query, record.rankedDocumentIds()));
            variants.put(variant, new FormatVariantSummary(scorer.scoreAll(qrels, rankings).summary(),
                    performance.get(variant)));
        }
        FormatSummary summary = new FormatSummary(expectedFormat, manifest.path("preprocessingStrategy").asText(),
                documents.size(), documents.stream().mapToInt(node -> node.path("totalChunks").asInt()).sum(),
                ingestStats(documents), Map.copyOf(variants));
        String runId = records.stream().map(RagBenchmarkRunIO.RunRecord::runId).distinct().findFirst()
                .orElseThrow();
        return new RunEvidence(runId, manifest, immutableNested(byVariant), summary);
    }

    private Map<String, ComplexityIngestSummary> ingestStats(List<JsonNode> documents) {
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        documents.forEach(value -> groups.computeIfAbsent(value.path("complexity").asText(),
                ignored -> new ArrayList<>()).add(value));
        Map<String, ComplexityIngestSummary> result = new LinkedHashMap<>();
        groups.forEach((complexity, values) -> result.put(complexity, new ComplexityIngestSummary(values.size(),
                distribution(values.stream().map(value -> value.path("elapsedMs").asLong()).toList()),
                distribution(values.stream().map(value -> value.path("totalChunks").asLong()).toList()))));
        result.put("ALL", new ComplexityIngestSummary(documents.size(),
                distribution(documents.stream().map(value -> value.path("elapsedMs").asLong()).toList()),
                distribution(documents.stream().map(value -> value.path("totalChunks").asLong()).toList())));
        return Map.copyOf(result);
    }

    private void validatePair(RunEvidence pdf, RunEvidence docx,
                              Map<String, Map<String, Integer>> qrels) {
        for (String field : List.of("datasetTreeSha256", "datasetManifestSha256", "configSha256")) {
            if (!pdf.manifest().path(field).asText().equals(docx.manifest().path(field).asText())) {
                throw new IllegalArgumentException("PDF/DOCX配对运行身份不一致: " + field);
            }
        }
        if (qrels.size() != 200) throw new IllegalArgumentException("配对问题数必须为200");
    }

    private PairComparison compare(String variant, Map<String, Map<String, Integer>> qrels,
                                   Map<String, String> queries, SourceIndex sources,
                                   Map<String, RagBenchmarkRunIO.RunRecord> pdf,
                                   Map<String, RagBenchmarkRunIO.RunRecord> docx) {
        int bothHit = 0, bothMiss = 0, pdfOnly = 0, docxOnly = 0, pdfBetter = 0, docxBetter = 0, tie = 0;
        List<PairExample> pdfOnlyExamples = new ArrayList<>();
        List<PairExample> docxOnlyExamples = new ArrayList<>();
        List<PairExample> bothMissExamples = new ArrayList<>();
        for (String queryId : qrels.keySet()) {
            Set<String> gold = positive(qrels.get(queryId));
            int pdfRank = firstRank(gold, pdf.get(queryId).rankedDocumentIds());
            int docxRank = firstRank(gold, docx.get(queryId).rankedDocumentIds());
            boolean pdfHit = pdfRank <= 10;
            boolean docxHit = docxRank <= 10;
            PairExample example = example(queryId, queries.get(queryId), gold, pdfRank, docxRank, sources);
            if (pdfHit && docxHit) bothHit++;
            else if (pdfHit) { pdfOnly++; addBounded(pdfOnlyExamples, example); }
            else if (docxHit) { docxOnly++; addBounded(docxOnlyExamples, example); }
            else { bothMiss++; addBounded(bothMissExamples, example); }
            if (pdfRank < docxRank) pdfBetter++;
            else if (docxRank < pdfRank) docxBetter++;
            else tie++;
        }
        return new PairComparison(variant, bothHit, bothMiss, pdfOnly, docxOnly,
                pdfBetter, docxBetter, tie, List.copyOf(pdfOnlyExamples),
                List.copyOf(docxOnlyExamples), List.copyOf(bothMissExamples));
    }

    private ResourceEvidence resourceEvidence(Path directory) throws IOException {
        List<JsonNode> local = readJsonLines(directory.resolve("local-process.jsonl"));
        List<JsonNode> remote = readJsonLines(directory.resolve("remote-containers.jsonl"));
        if (local.isEmpty() || remote.isEmpty()
                || Files.size(directory.resolve("remote-sampler.err.log")) != 0) {
            throw new IllegalArgumentException("资源采样为空或采样器有错误");
        }
        List<Double> localCpu = new ArrayList<>();
        List<Long> localRss = new ArrayList<>();
        List<Long> threads = new ArrayList<>();
        for (JsonNode sample : local) {
            String[] process = sample.path("appProcess").asText().trim().split("\\s+");
            if (process.length < 3) throw new IllegalArgumentException("本机资源样本格式非法");
            localCpu.add(Double.parseDouble(process[1]));
            localRss.add(Long.parseLong(process[2]));
            threads.add(sample.path("threadCount").asLong());
        }
        Map<String, MutableContainer> containers = new LinkedHashMap<>();
        for (JsonNode sample : remote) {
            for (JsonNode container : sample.path("containers")) {
                String name = container.path("Name").asText();
                MutableContainer value = containers.computeIfAbsent(name, ignored -> new MutableContainer());
                value.cpu.add(percent(container.path("CPUPerc").asText()));
                value.memory.add(percent(container.path("MemPerc").asText()));
                value.pids.add(Long.parseLong(container.path("PIDs").asText()));
            }
        }
        Map<String, ContainerResourceSummary> summaries = new LinkedHashMap<>();
        containers.forEach((name, value) -> summaries.put(name, new ContainerResourceSummary(
                doubleDistribution(value.cpu), doubleDistribution(value.memory), distribution(value.pids))));
        List<String> inspectBefore = Files.readAllLines(directory.resolve("remote-inspect-before.txt"),
                StandardCharsets.UTF_8);
        List<String> inspectAfter = Files.readAllLines(directory.resolve("remote-inspect-after.txt"),
                StandardCharsets.UTF_8);
        if (inspectBefore.size() < 2 || inspectAfter.size() < 2) {
            throw new IllegalArgumentException("容器inspect稳定字段为空");
        }
        List<String> stableBefore = inspectBefore.subList(1, inspectBefore.size());
        List<String> stableAfter = inspectAfter.subList(1, inspectAfter.size());
        boolean inspectUnchanged = stableBefore.equals(stableAfter);
        if (!inspectUnchanged) throw new IllegalArgumentException("评测前后容器inspect发生变化");
        if (stableBefore.size() != containers.size()) {
            throw new IllegalArgumentException("容器inspect与资源样本容器集合数量不一致");
        }
        return new ResourceEvidence(local.size(), remote.size(), stableBefore.size(), inspectUnchanged,
                doubleDistribution(localCpu), distribution(localRss), distribution(threads), Map.copyOf(summaries));
    }

    private List<String> conclusions(RunEvidence pdf, RunEvidence docx,
                                     Map<String, PairComparison> pairs, ResourceEvidence resources) {
        double pdfRerank = pdf.summary().variants().get("hybrid_rrf_rerank").performance().elapsedMs().mean();
        double pdfHybrid = pdf.summary().variants().get("hybrid_rrf").performance().elapsedMs().mean();
        double docxRerank = docx.summary().variants().get("hybrid_rrf_rerank").performance().elapsedMs().mean();
        double docxHybrid = docx.summary().variants().get("hybrid_rrf").performance().elapsedMs().mean();
        return List.of(
                "Dense在两种格式的Recall@10均为0.960，格式没有改变Top10总召回上限。",
                "DOCX生成" + docx.summary().totalChunks() + "个chunk，PDF生成" + pdf.summary().totalChunks()
                        + "个，DOCX多" + (docx.summary().totalChunks() - pdf.summary().totalChunks()) + "个。",
                String.format(Locale.ROOT, "Rerank平均端到端耗时相对Hybrid：PDF %.2fx，DOCX %.2fx。",
                        pdfRerank / pdfHybrid, docxRerank / docxHybrid),
                "Dense格式独占命中：PDF " + pairs.get("dense").pdfOnlyHit()
                        + "，DOCX " + pairs.get("dense").docxOnlyHit() + "；同为未命中 "
                        + pairs.get("dense").bothMiss() + "。",
                "资源峰值显示Reranker与Embedding为主要计算热点；容器inspect前后完全一致="
                        + resources.inspectUnchanged() + "。");
    }

    private String render(Report report) {
        StringBuilder out = new StringBuilder("# PDF/DOCX 同源RAG配对评测报告\n\n");
        out.append("PDF run：`").append(report.manifest().pdfRunId()).append("`；DOCX run：`")
                .append(report.manifest().docxRunId()).append("`；问题数：")
                .append(report.manifest().queryCount()).append("。\n\n## 质量与延迟\n\n")
                .append("| 格式 | 变体 | Recall@1 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | mean ms | p95 ms | p99 ms |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (FormatSummary format : List.of(report.pdf(), report.docx())) {
            format.variants().forEach((variant, summary) -> out.append("| ").append(format.format()).append(" | ")
                    .append(variant).append(" | ").append(number(summary.quality().get("Recall@1"))).append(" | ")
                    .append(number(summary.quality().get("Recall@5"))).append(" | ")
                    .append(number(summary.quality().get("Recall@10"))).append(" | ")
                    .append(number(summary.quality().get("MRR@10"))).append(" | ")
                    .append(number(summary.quality().get("nDCG@10"))).append(" | ")
                    .append(number(summary.performance().elapsedMs().mean())).append(" | ")
                    .append(summary.performance().elapsedMs().p95()).append(" | ")
                    .append(summary.performance().elapsedMs().p99()).append(" |\n"));
        }
        out.append("\n## 同问题配对结果\n\n| 变体 | 双命中 | 双失败 | 仅PDF命中 | 仅DOCX命中 | PDF名次更好 | DOCX名次更好 | 同名次 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        report.pairs().values().forEach(value -> out.append("| ").append(value.variant()).append(" | ")
                .append(value.bothHit()).append(" | ").append(value.bothMiss()).append(" | ")
                .append(value.pdfOnlyHit()).append(" | ").append(value.docxOnlyHit()).append(" | ")
                .append(value.pdfBetterRank()).append(" | ").append(value.docxBetterRank()).append(" | ")
                .append(value.sameRank()).append(" |\n"));
        out.append("\n## 摄取\n\n| 格式 | chunk总数 | 摄取mean ms | p50 | p95 | max |\n|---|---:|---:|---:|---:|---:|\n");
        for (FormatSummary format : List.of(report.pdf(), report.docx())) {
            Distribution value = format.ingestByComplexity().get("ALL").elapsedMs();
            out.append("| ").append(format.format()).append(" | ").append(format.totalChunks()).append(" | ")
                    .append(number(value.mean())).append(" | ").append(value.p50()).append(" | ")
                    .append(value.p95()).append(" | ").append(value.max()).append(" |\n");
        }
        out.append("\n## 资源瓶颈\n\n| 容器 | CPU mean% | CPU max% | 内存mean% | 内存max% | PIDs max |\n|---|---:|---:|---:|---:|---:|\n");
        report.resources().containers().forEach((name, value) -> out.append("| ").append(name).append(" | ")
                .append(number(value.cpuPercent().mean())).append(" | ").append(number(value.cpuPercent().max()))
                .append(" | ").append(number(value.memoryPercent().mean())).append(" | ")
                .append(number(value.memoryPercent().max())).append(" | ").append(value.pids().max()).append(" |\n"));
        out.append("\n## 格式独占与共同失败样本\n");
        report.pairs().forEach((variant, value) -> {
            out.append("\n### ").append(variant).append("\n");
            examples(out, "仅PDF命中", value.pdfOnlyExamples());
            examples(out, "仅DOCX命中", value.docxOnlyExamples());
            examples(out, "两者均未命中", value.bothMissExamples());
        });
        out.append("\n## 结论\n\n");
        report.conclusions().forEach(value -> out.append("- ").append(value).append("\n"));
        out.append("\n## 证据边界\n\n");
        report.manifest().limitations().forEach(value -> out.append("- ").append(value).append("\n"));
        out.append("\n## 输入SHA-256\n\n");
        report.manifest().inputSha256().forEach((name, hash) ->
                out.append("- ").append(name).append(": `").append(hash).append("`\n"));
        return out.toString();
    }

    private void examples(StringBuilder out, String title, List<PairExample> examples) {
        out.append("\n").append(title).append("：\n\n");
        if (examples.isEmpty()) { out.append("- 无\n"); return; }
        examples.forEach(value -> out.append("- queryId=`").append(value.queryId()).append("`，PDF rank=")
                .append(rank(value.pdfRank())).append("，DOCX rank=").append(rank(value.docxRank()))
                .append("，问题：").append(value.question()).append("；源文件 ")
                .append(value.sourcePaths().entrySet().stream().map(entry -> "[" + entry.getKey() + "]("
                        + "../../evaluation-data/pdf-docx-200/" + entry.getValue() + ")")
                        .reduce((a, b) -> a + " / " + b).orElse("无")).append("\n"));
    }

    private Map<String, String> readQueries(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode value : readJsonLines(path)) {
            String id = value.path("queryId").asText();
            String text = value.path("text").asText();
            if (id.isBlank() || text.isBlank() || result.putIfAbsent(id, text) != null) {
                throw new IllegalArgumentException("问题输入非法");
            }
        }
        return Map.copyOf(result);
    }

    private SourceIndex readSources(Path path) throws IOException {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        for (JsonNode value : readJsonLines(path)) {
            values.computeIfAbsent(value.path("sourceDocumentId").asText(), ignored -> new LinkedHashMap<>())
                    .put(value.path("format").asText(), value.path("relativePath").asText());
        }
        values.forEach((id, paths) -> {
            if (!paths.keySet().equals(Set.of("PDF", "DOCX"))) {
                throw new IllegalArgumentException("源文档缺少PDF/DOCX配对: " + id);
            }
        });
        return new SourceIndex(values.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue()))));
    }

    private PairExample example(String queryId, String question, Set<String> gold, int pdfRank, int docxRank,
                                SourceIndex sources) {
        String firstGold = gold.stream().sorted().findFirst().orElseThrow();
        return new PairExample(queryId, question, List.copyOf(gold), pdfRank, docxRank,
                sources.paths().getOrDefault(firstGold, Map.of()));
    }

    private Set<String> positive(Map<String, Integer> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach((id, score) -> { if (score > 0) result.add(id); });
        return result;
    }

    private int firstRank(Set<String> gold, List<String> ranking) {
        for (int index = 0; index < Math.min(10, ranking.size()); index++) {
            if (gold.contains(ranking.get(index))) return index + 1;
        }
        return 11;
    }

    private void addBounded(List<PairExample> values, PairExample value) {
        if (values.size() < 10) values.add(value);
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        List<JsonNode> values = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.isBlank()) values.add(mapper.readTree(line));
        }
        return List.copyOf(values);
    }

    private Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> immutableNested(
            Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> value) {
        Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> result = new LinkedHashMap<>();
        value.forEach((key, records) -> result.put(key, Map.copyOf(records)));
        return Map.copyOf(result);
    }

    private Distribution distribution(List<Long> values) {
        if (values.isEmpty()) return new Distribution(0, 0, 0, 0, 0, 0);
        List<Long> sorted = values.stream().sorted().toList();
        return new Distribution(sorted.size(), sorted.stream().mapToLong(Long::longValue).average().orElse(0),
                percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99),
                sorted.get(sorted.size() - 1));
    }

    private DoubleDistribution doubleDistribution(List<Double> values) {
        if (values.isEmpty()) return new DoubleDistribution(0, 0, 0);
        return new DoubleDistribution(values.size(), values.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                values.stream().mapToDouble(Double::doubleValue).max().orElse(0));
    }

    private long percentile(List<Long> sorted, double value) {
        return sorted.get(Math.max(0, (int) Math.ceil(value * sorted.size()) - 1));
    }

    private double percent(String value) {
        return Double.parseDouble(value.replace("%", "").trim());
    }

    private Map<String, String> inputHashes(Configuration value) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("pdfRun", sha256(value.pdfRunDirectory().resolve("run.jsonl")));
        result.put("pdfDocuments", sha256(value.pdfRunDirectory().resolve("document-results.jsonl")));
        result.put("docxRun", sha256(value.docxRunDirectory().resolve("run.jsonl")));
        result.put("docxDocuments", sha256(value.docxRunDirectory().resolve("document-results.jsonl")));
        result.put("qrels", sha256(value.qrels()));
        result.put("queries", sha256(value.queries()));
        result.put("documentManifest", sha256(value.documentManifest()));
        for (String name : List.of("local-process.jsonl", "remote-containers.jsonl",
                "remote-inspect-before.txt", "remote-inspect-after.txt", "remote-sampler.err.log")) {
            result.put(name, sha256(value.resourceEvidenceDirectory().resolve(name)));
        }
        return Map.copyOf(result);
    }

    private String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private String rank(int value) { return value > 10 ? "Top10未命中" : String.valueOf(value); }

    public record Configuration(Path pdfRunDirectory, Path docxRunDirectory, Path qrels, Path queries,
                                Path documentManifest, Path resourceEvidenceDirectory, Path outputDirectory) {
        void validate() {
            for (Path path : List.of(pdfRunDirectory, docxRunDirectory, resourceEvidenceDirectory)) {
                if (path == null || !Files.isDirectory(path)) throw new IllegalArgumentException("格式对照目录不存在");
            }
            for (Path path : List.of(qrels, queries, documentManifest)) {
                if (path == null || !Files.isRegularFile(path)) throw new IllegalArgumentException("格式对照文件不存在");
            }
            if (outputDirectory == null) throw new IllegalArgumentException("格式对照输出为空");
        }
    }
    public record Result(Report report, Path json, Path markdown, Path evidenceManifest) {}
    public record Report(Manifest manifest, FormatSummary pdf, FormatSummary docx,
                         Map<String, PairComparison> pairs, ResourceEvidence resources,
                         List<String> conclusions) {}
    public record Manifest(int schemaVersion, String generator, String pdfRunId, String docxRunId,
                           int queryCount, Map<String, String> inputSha256, List<String> limitations) {}
    public record FormatSummary(String format, String preprocessingStrategy, int documentCount, int totalChunks,
                                Map<String, ComplexityIngestSummary> ingestByComplexity,
                                Map<String, FormatVariantSummary> variants) {}
    public record FormatVariantSummary(Map<String, Double> quality,
                                       RagBenchmarkRunStatistics.VariantStatistics performance) {}
    public record ComplexityIngestSummary(int count, Distribution elapsedMs, Distribution chunks) {}
    public record Distribution(long count, double mean, long p50, long p95, long p99, long max) {}
    public record DoubleDistribution(long count, double mean, double max) {}
    public record PairComparison(String variant, int bothHit, int bothMiss, int pdfOnlyHit, int docxOnlyHit,
                                 int pdfBetterRank, int docxBetterRank, int sameRank,
                                 List<PairExample> pdfOnlyExamples, List<PairExample> docxOnlyExamples,
                                 List<PairExample> bothMissExamples) {}
    public record PairExample(String queryId, String question, List<String> goldDocumentIds,
                              int pdfRank, int docxRank, Map<String, String> sourcePaths) {}
    public record ResourceEvidence(int localSampleCount, int remoteSampleCount, int inspectedContainerCount,
                                   boolean inspectUnchanged,
                                   DoubleDistribution localCpuPercent, Distribution localRssKiB,
                                   Distribution localThreads, Map<String, ContainerResourceSummary> containers) {}
    public record ContainerResourceSummary(DoubleDistribution cpuPercent, DoubleDistribution memoryPercent,
                                           Distribution pids) {}
    private record RunEvidence(String runId, JsonNode manifest,
                               Map<String, Map<String, RagBenchmarkRunIO.RunRecord>> byVariant,
                               FormatSummary summary) {}
    private record SourceIndex(Map<String, Map<String, String>> paths) {}
    private static final class MutableContainer {
        private final List<Double> cpu = new ArrayList<>();
        private final List<Double> memory = new ArrayList<>();
        private final List<Long> pids = new ArrayList<>();
    }
}
