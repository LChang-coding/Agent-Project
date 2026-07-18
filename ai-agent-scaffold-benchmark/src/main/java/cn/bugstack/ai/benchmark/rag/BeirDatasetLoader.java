package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 严格读取 BEIR corpus.jsonl、queries.jsonl 和 qrels TSV。 */
public final class BeirDatasetLoader {

    private final ObjectMapper objectMapper;

    public BeirDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RagBenchmarkDataset load(Path corpus, Path queries, Path qrels, Limits limits) throws IOException {
        if (limits == null) limits = Limits.defaults();
        Map<String, RagBenchmarkDataset.Document> documents = readDocuments(corpus, limits);
        Map<String, String> queryValues = readQueries(queries, limits);
        Map<String, Map<String, Integer>> relevance = readQrels(qrels, limits);
        Map<String, String> evaluatedQueries = new LinkedHashMap<>();
        relevance.keySet().forEach(queryId -> {
            String query = queryValues.get(queryId);
            if (query == null) throw new IllegalArgumentException("qrels引用了不存在的查询: " + queryId);
            evaluatedQueries.put(queryId, query);
        });
        return new RagBenchmarkDataset(documents, evaluatedQueries, relevance);
    }

    public Map<String, Map<String, Integer>> loadQrels(Path qrels, Limits limits) throws IOException {
        return Map.copyOf(readQrels(qrels, limits == null ? Limits.defaults() : limits));
    }

    private Map<String, RagBenchmarkDataset.Document> readDocuments(Path path, Limits limits) throws IOException {
        Map<String, RagBenchmarkDataset.Document> result = new LinkedHashMap<>();
        readJsonLines(path, limits.maxCorpusDocuments(), limits.maxLineChars(), node -> {
            String id = requiredText(node, "_id");
            RagBenchmarkDataset.Document previous = result.putIfAbsent(id, new RagBenchmarkDataset.Document(
                    id, optionalText(node, "title"), requiredText(node, "text")));
            if (previous != null) throw new IllegalArgumentException("语料文档ID重复: " + id);
        });
        return result;
    }

    private Map<String, String> readQueries(Path path, Limits limits) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        readJsonLines(path, limits.maxQueries(), limits.maxLineChars(), node -> {
            String id = requiredText(node, "_id");
            if (result.putIfAbsent(id, requiredText(node, "text")) != null) {
                throw new IllegalArgumentException("查询ID重复: " + id);
            }
        });
        return result;
    }

    private Map<String, Map<String, Integer>> readQrels(Path path, Limits limits) throws IOException {
        validateReadableFile(path);
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (line.length() > limits.maxLineChars()) throw new IllegalArgumentException("qrels单行超过上限");
                if (line.isBlank()) continue;
                String[] fields = line.split("\\t", -1);
                if (first && fields.length >= 3 && fields[0].toLowerCase(Locale.ROOT).contains("query")) {
                    first = false;
                    continue;
                }
                first = false;
                if (fields.length < 3) throw new IllegalArgumentException("qrels必须至少包含query-id、corpus-id、score");
                int score;
                try {
                    score = Integer.parseInt(fields[2].trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("qrels相关性分数不是整数", exception);
                }
                Map<String, Integer> queryQrels = result.computeIfAbsent(fields[0].trim(), ignored -> new LinkedHashMap<>());
                if (queryQrels.putIfAbsent(fields[1].trim(), score) != null) {
                    throw new IllegalArgumentException("qrels查询-文档对重复");
                }
                if (++rows > limits.maxQrelRows()) throw new IllegalArgumentException("qrels行数超过上限");
            }
        }
        return result;
    }

    private void readJsonLines(Path path, int maxRows, int maxLineChars, JsonLineConsumer consumer) throws IOException {
        validateReadableFile(path);
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.length() > maxLineChars) throw new IllegalArgumentException("JSONL单行超过上限");
                if (++rows > maxRows) throw new IllegalArgumentException("JSONL记录数超过上限");
                consumer.accept(objectMapper.readTree(line));
            }
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) throw new IllegalArgumentException("BEIR字段不能为空: " + field);
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText().strip();
    }

    private void validateReadableFile(Path path) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("基准输入文件不存在或不可读: " + path);
        }
    }

    @FunctionalInterface
    private interface JsonLineConsumer { void accept(JsonNode node) throws IOException; }

    public record Limits(int maxCorpusDocuments, int maxQueries, int maxQrelRows, int maxLineChars) {
        public Limits {
            if (maxCorpusDocuments < 1 || maxQueries < 1 || maxQrelRows < 1 || maxLineChars < 128) {
                throw new IllegalArgumentException("BEIR读取上限非法");
            }
        }
        public static Limits defaults() { return new Limits(1_000_000, 100_000, 5_000_000, 2_000_000); }
    }
}
