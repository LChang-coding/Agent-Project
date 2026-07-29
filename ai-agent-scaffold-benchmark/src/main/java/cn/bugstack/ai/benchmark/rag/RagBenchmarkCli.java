package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RAG 基准数据准备、生产黑盒执行与离线评分入口。 */
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
            case "prepare-formats" -> prepareFormats(options);
            case "validate-formats" -> validateFormats(options);
            case "run-format" -> runFormat(options);
            case "persist-evaluation" -> persistEvaluation(options);
            case "score" -> score(options);
            case "run" -> run(options);
            case "evaluate" -> evaluate(options);
            case "load" -> load(options);
            case "failure-cases" -> failureCases(options);
            case "diagnose-cases" -> diagnoseCases(options);
            case "diagnostic-report" -> diagnosticReport(options);
            default -> throw new IllegalArgumentException("不支持的命令: " + args[0]);
        }
    }

    private static void persistEvaluation(Map<String, String> options) throws Exception {
        String url = environment(options, "db-url-env", "RAG_BENCHMARK_DB_URL");
        String user = environment(options, "db-user-env", "RAG_BENCHMARK_DB_USER");
        String password = environment(options, "db-password-env", "RAG_BENCHMARK_DB_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            RagBenchmarkJdbcStore.Result result = new RagBenchmarkJdbcStore(new ObjectMapper()).persist(connection,
                    new RagBenchmarkJdbcStore.Configuration(path(options, "dataset-manifest"),
                            path(options, "run-manifest"), path(options, "document-results"),
                            path(options, "run-records"),
                            path(options, "qrels"), required(options, "format"),
                            required(options, "preprocessing-strategy"),
                            required(options, "preprocessing-revision"), required(options, "git-commit"),
                            sha256Value(options, "config-sha256"), required(options, "artifact-root")));
            System.out.printf("persisted evaluation runId=%s dataset=%s documents=%d queryResults=%d "
                            + "aggregates=%d%n",
                    result.runId(), result.datasetId(), result.documentResultCount(),
                    result.queryResultCount(), result.aggregateCount());
        }
    }

    private static void validateFormats(Map<String, String> options) throws IOException {
        Path root = path(options, "prepared");
        RagFormatDatasetValidator.Report report = new RagFormatDatasetValidator(new ObjectMapper()).validate(root);
        System.out.printf("validated format dataset valid=%s formatDocuments=%d pairedDocuments=%d queries=%d "
                        + "qrels=%d treeSha256=%s failures=%d%n",
                report.valid(), report.formatDocumentCount(), report.pairedDocumentCount(), report.queryCount(),
                report.qrelCount(), report.actualTreeSha256(), report.failures().size());
        if (!report.valid()) {
            report.failures().forEach(value -> System.err.println("validation failure: " + value));
            throw new IllegalStateException("格式评测数据校验失败");
        }
    }

    private static void prepareFormats(Map<String, String> options) throws IOException {
        RagFormatDatasetBuilder.Manifest manifest = new RagFormatDatasetBuilder(new ObjectMapper()).build(
                new RagFormatDatasetBuilder.Configuration(required(options, "dataset"),
                        path(options, "corpus"), path(options, "queries"), path(options, "qrels"),
                        path(options, "out"), required(options, "source-url"),
                        required(options, "source-revision"), required(options, "license"),
                        number(options, "seed", 20260729L)));
        System.out.printf("prepared format dataset=%s pairedDocuments=%d queries=%d treeSha256=%s out=%s%n",
                manifest.datasetName(), manifest.pairedDocumentCount(), manifest.queryCount(),
                manifest.treeSha256(), path(options, "out"));
    }

    private static void runFormat(Map<String, String> options) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Remote remote = remote(options, objectMapper);
        RagFormatBenchmarkRunner.Configuration configuration = new RagFormatBenchmarkRunner.Configuration(
                required(options, "run-id"), remote.baseUrl(), remote.credentialSource(),
                required(options, "code-revision"), path(options, "dataset"),
                required(options, "format").toUpperCase(),
                required(options, "preprocessing-strategy"),
                required(options, "preprocessing-revision"),
                sha256Value(options, "config-sha256"), path(options, "out"),
                number(options, "seed", 20260729L), nonNegativeInteger(options, "warmup-queries", 5),
                Duration.ofMillis(integer(options, "poll-ms", 1000)),
                Duration.ofSeconds(integer(options, "ingest-timeout-seconds", 1800)));
        RagFormatBenchmarkRunner.Result result = new RagFormatBenchmarkRunner(objectMapper, remote.client())
                .run(configuration);
        System.out.printf("completed format runId=%s knowledgeBaseId=%s documents=%d queryResults=%d out=%s%n",
                result.runId(), result.knowledgeBaseId(), result.completedDocumentCount(),
                result.completedQueryResultCount(), configuration.outputDirectory());
    }

    private static void failureCases(Map<String, String> options) throws IOException {
        Path json = path(options, "out-json");
        Path markdown = path(options, "out-markdown");
        RagFailureCaseReporter reporter = new RagFailureCaseReporter(new ObjectMapper());
        RagFailureCaseReporter.Report report = reporter.generate(new RagFailureCaseReporter.Configuration(
                path(options, "queries"), path(options, "qrels"), path(options, "documents"),
                path(options, "document-map"), path(options, "run"), integer(options, "max-per-category", 3)));
        reporter.write(report, json, markdown);
        System.out.printf("failure-cases completed queries=%d json=%s markdown=%s%n",
                report.manifest().queryCount(), json, markdown);
    }

    private static void diagnoseCases(Map<String, String> options) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Remote remote = remote(options, objectMapper);
        RagDiagnosticCaseRunner.Result result = new RagDiagnosticCaseRunner(objectMapper, remote.client()).run(
                new RagDiagnosticCaseRunner.Configuration(required(options, "run-id"),
                        required(options, "code-revision"), path(options, "case-report"),
                        path(options, "targets"), path(options, "out"), integer(options, "max-queries", 100),
                        remote.requestTimeoutSeconds()));
        System.out.printf("diagnose-cases completed queries=%d records=%d sha256=%s out=%s%n",
                result.queryCount(), result.recordCount(), result.recordsSha256(), result.records().getParent());
    }

    private static void diagnosticReport(Map<String, String> options) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        RagInternalDiagnosticReporter reporter = new RagInternalDiagnosticReporter(objectMapper);
        RagInternalDiagnosticReporter.Report report = reporter.generate(new RagInternalDiagnosticReporter.Configuration(
                path(options, "failure-report"), path(options, "diagnostics"),
                path(options, "diagnostic-manifest"), path(options, "qrels"),
                path(options, "documents"), path(options, "document-map")));
        reporter.write(report, path(options, "out-json"), path(options, "out-markdown"));
        System.out.printf("diagnostic-report completed queries=%d records=%d integrity=%s%n",
                report.manifest().queryCount(), report.manifest().recordCount(), report.manifest().integrityHealthy());
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

    private static void run(Map<String, String> options) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Remote remote = remote(options, objectMapper);
        RagBenchmarkRunner.Configuration configuration = new RagBenchmarkRunner.Configuration(
                required(options, "run-id"), remote.baseUrl(), remote.credentialSource(),
                required(options, "code-revision"),
                path(options, "prepared"), path(options, "out"), number(options, "seed", 20260719L),
                nonNegativeInteger(options, "warmup-queries", 10),
                Duration.ofMillis(integer(options, "poll-ms", 1000)),
                Duration.ofSeconds(integer(options, "ingest-timeout-seconds", 3600)),
                remote.requestTimeoutSeconds());
        RagBenchmarkRunner.Result result = new RagBenchmarkRunner(objectMapper, remote.client()).run(configuration);
        System.out.printf("completed runId=%s knowledgeBaseId=%s taskId=%s out=%s%n",
                result.runId(), result.knowledgeBaseId(), result.taskId(), configuration.runDirectory());
    }

    private static void load(Map<String, String> options) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Remote remote = remote(options, objectMapper);
        RagLoadBenchmarkRunner.Configuration configuration = new RagLoadBenchmarkRunner.Configuration(
                required(options, "run-id"), remote.baseUrl(), remote.credentialSource(),
                required(options, "code-revision"), path(options, "prepared"), path(options, "targets"),
                path(options, "out"), number(options, "seed", 20260719L),
                positiveIntegers(options.getOrDefault("concurrency-levels", "1,10"), "concurrency-levels"),
                nonNegativeInteger(options, "warmup-per-variant", 10),
                integer(options, "requests-per-variant", 100),
                Duration.ofSeconds(integer(options, "phase-timeout-seconds", 1800)),
                Duration.ofSeconds(remote.requestTimeoutSeconds()),
                Duration.ofSeconds(remote.connectTimeoutSeconds()),
                sha256Value(options, "cli-jar-sha256"), sha256Value(options, "app-jar-sha256"),
                required(options, "resource-evidence"));
        RagLoadBenchmarkRunner.Result result = new RagLoadBenchmarkRunner(objectMapper, remote.client())
                .run(configuration);
        System.out.printf("completed load runId=%s requests=%d levels=%s out=%s%n", result.runId(),
                result.records().size(), configuration.concurrencyLevels(), configuration.outputDirectory());
    }

    private static void evaluate(Map<String, String> options) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Remote remote = remote(options, objectMapper);
        RagBenchmarkRunner.EvaluationConfiguration configuration =
                new RagBenchmarkRunner.EvaluationConfiguration(required(options, "run-id"), remote.baseUrl(),
                        remote.credentialSource(), required(options, "code-revision"), path(options, "prepared"),
                        path(options, "targets"), path(options, "out"), number(options, "seed", 20260719L),
                        nonNegativeInteger(options, "warmup-queries", 10), optionalPath(options, "resume-from"),
                        remote.requestTimeoutSeconds());
        RagBenchmarkRunner.Result result = new RagBenchmarkRunner(objectMapper, remote.client())
                .evaluate(configuration);
        System.out.printf("completed evaluate runId=%s out=%s%n", result.runId(), configuration.runDirectory());
    }

    private static Remote remote(Map<String, String> options, ObjectMapper objectMapper) {
        String tokenEnvironment = options.getOrDefault("token-env", "RAG_BENCHMARK_ACCESS_TOKEN");
        if (!tokenEnvironment.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("--token-env 必须是合法的环境变量名");
        }
        String token = System.getenv(tokenEnvironment);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("环境变量 " + tokenEnvironment + " 未设置");
        }
        URI baseUrl = URI.create(required(options, "base-url"));
        if (!List.of("http", "https").contains(baseUrl.getScheme())) {
            throw new IllegalArgumentException("--base-url 只允许 http/https");
        }
        int connectTimeoutSeconds = integer(options, "connect-timeout-seconds", 10);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(
                connectTimeoutSeconds)).build();
        Duration requestTimeout = Duration.ofSeconds(integer(options, "request-timeout-seconds", 120));
        int maxResponseBytes = integer(options, "max-response-bytes", 8 * 1024 * 1024);
        String usernameEnvironment = options.getOrDefault("username-env", "RAG_BENCHMARK_USERNAME");
        String passwordEnvironment = options.getOrDefault("password-env", "RAG_BENCHMARK_PASSWORD");
        String username = System.getenv(usernameEnvironment);
        String password = System.getenv(passwordEnvironment);
        boolean refreshEnabled = username != null && !username.isBlank() && password != null && !password.isBlank();
        RagBenchmarkHttpClient client = refreshEnabled
                ? new RagBenchmarkHttpClient(httpClient, objectMapper, baseUrl,
                new RefreshingLoginTokenProvider(httpClient, objectMapper, baseUrl, token, username, password,
                        requestTimeout, maxResponseBytes), requestTimeout, maxResponseBytes)
                : new RagBenchmarkHttpClient(httpClient, objectMapper, baseUrl, token,
                requestTimeout, maxResponseBytes);
        return new Remote(baseUrl, "environment:" + tokenEnvironment
                + (refreshEnabled ? ";refresh=enabled" : ";refresh=disabled"), client,
                Math.toIntExact(requestTimeout.toSeconds()), connectTimeoutSeconds);
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
    private static Path optionalPath(Map<String, String> values, String name) {
        String value = values.get(name);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }
    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数 --" + name);
        return value;
    }
    private static String environment(Map<String, String> values, String optionName, String fallbackName) {
        String name = values.getOrDefault(optionName, fallbackName);
        if (!name.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("--" + optionName + " 必须是合法环境变量名");
        }
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("环境变量 " + name + " 未设置");
        return value;
    }
    private static String sha256Value(Map<String, String> values, String name) {
        String value = required(values, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("--" + name + " 必须是小写SHA-256");
        }
        return value;
    }
    private static int integer(Map<String, String> values, String name, int fallback) {
        long value = number(values, name, fallback);
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("参数超出范围 --" + name);
        return (int) value;
    }
    private static int nonNegativeInteger(Map<String, String> values, String name, int fallback) {
        long value = number(values, name, fallback);
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("参数超出范围 --" + name);
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
    private static List<Integer> positiveIntegers(String value, String name) {
        try {
            List<Integer> values = java.util.Arrays.stream(value.split(",", -1))
                    .map(String::trim).map(Integer::valueOf).toList();
            if (values.isEmpty() || values.stream().anyMatch(item -> item < 1)) {
                throw new NumberFormatException();
            }
            return values;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " 必须是逗号分隔的正整数", exception);
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
        lines.add("prepare-formats --corpus corpus.jsonl --queries queries.jsonl --qrels test.tsv --out DIR");
        lines.add("        --dataset NAME --source-url URL --source-revision REV --license LICENSE [--seed N]");
        lines.add("validate-formats --prepared DIR");
        lines.add("run-format --base-url http://HOST:PORT/api --dataset DIR --format PDF|DOCX");
        lines.add("        --out EMPTY_DIR --run-id ID --code-revision SHA1");
        lines.add("        --preprocessing-strategy NAME --preprocessing-revision REV --config-sha256 SHA256");
        lines.add("        [--token-env RAG_BENCHMARK_ACCESS_TOKEN --warmup-queries 5 --seed 20260729]");
        lines.add("        [--poll-ms 1000 --ingest-timeout-seconds 1800 --request-timeout-seconds 120]");
        lines.add("persist-evaluation --dataset-manifest FILE --run-manifest FILE");
        lines.add("        --document-results document-results.jsonl --run-records run.jsonl");
        lines.add("        --qrels FILE --format PDF|DOCX --preprocessing-strategy NAME");
        lines.add("        --preprocessing-revision REV --git-commit SHA1 --config-sha256 SHA256");
        lines.add("        --artifact-root RELATIVE_PATH [--db-url-env NAME --db-user-env NAME --db-password-env NAME]");
        lines.add("score   --qrels qrels.tsv --run run.jsonl --out metrics.json");
        lines.add("run     --base-url http://HOST:PORT/api --prepared DIR --out EMPTY_DIR --run-id ID");
        lines.add("        --code-revision GIT_COMMIT");
        lines.add("        [--token-env RAG_BENCHMARK_ACCESS_TOKEN --warmup-queries 10 --seed 20260719]");
        lines.add("        [--poll-ms 1000 --ingest-timeout-seconds 3600 --request-timeout-seconds 120]");
        lines.add("load    --base-url http://HOST:PORT/api --prepared DIR --targets targets.json --out EMPTY_DIR");
        lines.add("        --run-id ID --code-revision GIT_COMMIT --cli-jar-sha256 SHA256 --app-jar-sha256 SHA256");
        lines.add("        --resource-evidence FILE_OR_REFERENCE");
        lines.add("        [--concurrency-levels 1,10 --warmup-per-variant 10 --requests-per-variant 100]");
        lines.add("        [--phase-timeout-seconds 1800 --request-timeout-seconds 120]");
        lines.add("evaluate --base-url http://HOST:PORT/api --prepared DIR --targets targets.json --out EMPTY_DIR");
        lines.add("        --run-id ID --code-revision GIT_COMMIT [--warmup-queries 10 --seed 20260719]");
        lines.add("        [--resume-from FAILED_RUN_DIR --request-timeout-seconds 120]");
        lines.add("failure-cases --queries queries.jsonl --qrels qrels.tsv --documents benchmark.md");
        lines.add("        --document-map document-map.jsonl --run run.jsonl --out-json report.json");
        lines.add("        --out-markdown report.md [--max-per-category 3]");
        lines.add("diagnose-cases --base-url http://HOST:PORT/api --case-report report.json --targets targets.json");
        lines.add("        --out EMPTY_DIR --run-id ID --code-revision GIT_COMMIT [--max-queries 100]");
        lines.add("diagnostic-report --failure-report report.json --diagnostics diagnostic.jsonl");
        lines.add("        --diagnostic-manifest diagnostic-manifest.json --qrels qrels.tsv");
        lines.add("        --documents benchmark.md --document-map document-map.jsonl");
        lines.add("        --out-json report.json --out-markdown report.md");
        lines.forEach(System.out::println);
    }

    private record Remote(URI baseUrl, String credentialSource, RagBenchmarkHttpClient client,
                          int requestTimeoutSeconds, int connectTimeoutSeconds) {}
}
