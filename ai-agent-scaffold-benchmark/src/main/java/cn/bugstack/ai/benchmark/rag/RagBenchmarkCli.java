package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RAG 基准数据准备与离线评分入口。认证和在线执行由后续 run 子命令承接。 */
public final class RagBenchmarkCli {

    private RagBenchmarkCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0]) || "--help".equalsIgnoreCase(args[0])) {
            usage();
            return;
        }
        Map<String, String> options = options(args);
        switch (args[0].toLowerCase()) {
            case "prepare" -> prepare(options);
            case "score" -> score(options);
            default -> throw new IllegalArgumentException("不支持的命令: " + args[0]);
        }
    }

    private static void prepare(Map<String, String> options) throws IOException {
        Path corpus = path(options, "corpus");
        Path queries = path(options, "queries");
        Path qrels = path(options, "qrels");
        Path output = path(options, "out");
        long seed = number(options, "seed", 20260719L);
        int maxDocuments = integer(options, "max-documents", Integer.MAX_VALUE);
        int maxQueries = integer(options, "max-queries", Integer.MAX_VALUE);
        int shardMaxBytes = integer(options, "shard-max-bytes", 4 * 1024 * 1024);

        ObjectMapper objectMapper = new ObjectMapper();
        RagBenchmarkDataset full = new BeirDatasetLoader(objectMapper)
                .load(corpus, queries, qrels, BeirDatasetLoader.Limits.defaults());
        boolean subset = maxDocuments < full.documents().size() || maxQueries < full.qrels().size();
        RagBenchmarkDataset selected = subset
                ? full.deterministicSubset(Math.min(maxDocuments, full.documents().size()),
                Math.min(maxQueries, full.qrels().size()), seed) : full;
        RagBenchmarkArtifactWriter.Configuration configuration = new RagBenchmarkArtifactWriter.Configuration(
                required(options, "dataset"), required(options, "source-url"),
                required(options, "source-revision"), required(options, "license"),
                subset ? "positive-closed-deterministic-v1" : "full", seed, shardMaxBytes);
        RagBenchmarkArtifactWriter.Manifest manifest = new RagBenchmarkArtifactWriter(objectMapper).write(selected,
                output, configuration, new RagBenchmarkArtifactWriter.SourceFiles(corpus, queries, qrels));
        System.out.printf("prepared dataset=%s documents=%d queries=%d qrels=%d out=%s%n",
                manifest.datasetName(), manifest.documentCount(), manifest.queryCount(), manifest.qrelCount(), output);
    }

    private static void score(Map<String, String> options) throws IOException {
        Path qrelsPath = path(options, "qrels");
        Path runPath = path(options, "run");
        Path output = path(options, "out");
        if (Files.exists(output)) throw new IllegalArgumentException("禁止覆盖既有评分报告");
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Map<String, Integer>> qrels = new BeirDatasetLoader(objectMapper)
                .loadQrels(qrelsPath, BeirDatasetLoader.Limits.defaults());
        Map<String, Map<String, List<String>>> variants = new RagBenchmarkRunIO(objectMapper).read(runPath);
        RagRetrievalScorer scorer = new RagRetrievalScorer();
        Map<String, RagRetrievalScorer.AggregateMetrics> metrics = new LinkedHashMap<>();
        variants.forEach((variant, runs) -> metrics.put(variant, scorer.scoreAll(qrels, runs)));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("qrelsSha256", sha256(qrelsPath));
        manifest.put("runSha256", sha256(runPath));
        manifest.put("answerMetrics", "not_evaluated_no_gold_answers");
        new RagBenchmarkRunIO(objectMapper).writeReport(output, metrics, manifest);
        metrics.forEach((variant, value) -> System.out.printf(
                "%s queries=%d missing=%d Recall@10=%.6f MRR@10=%.6f nDCG@10=%.6f MAP@10=%.6f%n",
                variant, value.queryCount(), value.missingRunCount(), value.recallAt10(), value.mrrAt10(),
                value.ndcgAt10(), value.mapAt10()));
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("参数必须使用 --name value 成对传入");
            }
            String name = args[index].substring(2);
            if (values.putIfAbsent(name, args[index + 1]) != null) {
                throw new IllegalArgumentException("参数重复: --" + name);
            }
        }
        return values;
    }

    private static Path path(Map<String, String> values, String name) {
        return Path.of(required(values, name)).toAbsolutePath().normalize();
    }
    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数 --" + name);
        return value;
    }
    private static int integer(Map<String, String> values, String name, int fallback) {
        long value = number(values, name, fallback);
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("参数超出范围 --" + name);
        return (int) value;
    }
    private static long number(Map<String, String> values, String name, long fallback) {
        String value = values.get(name);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("参数必须是整数 --" + name, exception);
        }
    }
    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void usage() {
        List<String> lines = new ArrayList<>();
        lines.add("prepare --corpus corpus.jsonl --queries queries.jsonl --qrels test.tsv --out DIR");
        lines.add("        --dataset NAME --source-url URL --source-revision REV --license LICENSE");
        lines.add("        [--max-documents N --max-queries N --seed N --shard-max-bytes N]");
        lines.add("score   --qrels qrels.tsv --run run.jsonl --out metrics.json");
        lines.forEach(System.out::println);
    }
}
