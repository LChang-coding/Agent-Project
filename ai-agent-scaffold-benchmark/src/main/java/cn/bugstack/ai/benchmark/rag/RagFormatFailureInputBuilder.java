package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把成对格式数据集转换成失败分析器可直接消费的确定性文本证据。 */
public final class RagFormatFailureInputBuilder {

    private final ObjectMapper mapper;

    public RagFormatFailureInputBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Result build(Configuration configuration) throws IOException {
        configuration.validate();
        if (Files.exists(configuration.outputDirectory())) {
            throw new IllegalArgumentException("失败分析输入目录已存在，禁止覆盖");
        }
        Map<String, SourcePaths> paths = readPaths(configuration.documentsManifest());
        List<Gold> gold = readGold(configuration.gold());
        if (gold.size() != paths.size()) throw new IllegalArgumentException("gold文档数与格式文档对数不一致");

        Files.createDirectories(configuration.outputDirectory());
        Path queries = configuration.outputDirectory().resolve("queries.jsonl");
        Path documents = configuration.outputDirectory().resolve("documents.md");
        Path documentMap = configuration.outputDirectory().resolve("document-map.jsonl");
        Path manifest = configuration.outputDirectory().resolve("manifest.json");
        StringBuilder queryLines = new StringBuilder();
        StringBuilder markdown = new StringBuilder();
        StringBuilder mapLines = new StringBuilder();
        for (Gold value : gold) {
            SourcePaths sourcePaths = paths.get(value.documentId());
            if (sourcePaths == null) throw new IllegalArgumentException("gold文档缺少PDF/DOCX路径: " + value.documentId());
            ObjectNode query = mapper.createObjectNode().put("queryId", value.queryId()).put("text", value.query());
            queryLines.append(mapper.writeValueAsString(query)).append('\n');
            markdown.append("# ").append(value.evidenceMarker()).append(" — ").append(value.title()).append("\n\n")
                    .append(value.evidenceText()).append("\n\n");
            ObjectNode map = mapper.createObjectNode().put("documentId", value.documentId())
                    .put("headingMarker", value.evidenceMarker());
            map.putObject("sourcePaths").put("PDF", sourcePaths.pdf()).put("DOCX", sourcePaths.docx());
            mapLines.append(mapper.writeValueAsString(map)).append('\n');
        }
        Files.writeString(queries, queryLines, StandardCharsets.UTF_8);
        Files.writeString(documents, markdown, StandardCharsets.UTF_8);
        Files.writeString(documentMap, mapLines, StandardCharsets.UTF_8);
        Manifest value = new Manifest(1, "rag-format-failure-input-v1", gold.size(),
                sha256(configuration.gold()), sha256(configuration.documentsManifest()),
                sha256(queries), sha256(documents), sha256(documentMap));
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(manifest.toFile(), value);
        return new Result(queries, documents, documentMap, manifest, value);
    }

    private List<Gold> readGold(Path path) throws IOException {
        Map<String, Gold> byQuery = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                Gold value = new Gold(required(node, "queryId"), required(node, "query"),
                        required(node, "documentId"), required(node, "title"),
                        required(node, "evidenceText"), required(node, "evidenceMarker"));
                if (byQuery.putIfAbsent(value.queryId(), value) != null) {
                    throw new IllegalArgumentException("gold包含重复queryId: " + value.queryId());
                }
            }
        }
        return List.copyOf(byQuery.values());
    }

    private Map<String, SourcePaths> readPaths(Path path) throws IOException {
        Map<String, MutablePaths> values = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                String documentId = required(node, "sourceDocumentId");
                String format = required(node, "format");
                String relativePath = required(node, "relativePath");
                MutablePaths mutable = values.computeIfAbsent(documentId, ignored -> new MutablePaths());
                if ("PDF".equals(format)) mutable.pdf = unique(mutable.pdf, relativePath, documentId, format);
                else if ("DOCX".equals(format)) mutable.docx = unique(mutable.docx, relativePath, documentId, format);
                else throw new IllegalArgumentException("文档manifest包含未知格式: " + format);
            }
        }
        Map<String, SourcePaths> result = new LinkedHashMap<>();
        values.forEach((id, value) -> {
            if (value.pdf == null || value.docx == null) throw new IllegalArgumentException("文档未成对: " + id);
            result.put(id, new SourcePaths(value.pdf, value.docx));
        });
        return result;
    }

    private String unique(String existing, String value, String documentId, String format) {
        if (existing != null) throw new IllegalArgumentException("文档格式路径重复: " + documentId + "/" + format);
        return value;
    }

    private String required(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) throw new IllegalArgumentException("输入缺少字段: " + name);
        return value;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    public record Configuration(Path gold, Path documentsManifest, Path outputDirectory) {
        void validate() {
            for (Path input : List.of(gold, documentsManifest)) {
                if (input == null || !Files.isRegularFile(input) || !Files.isReadable(input)) {
                    throw new IllegalArgumentException("格式失败分析输入不存在或不可读: " + input);
                }
            }
            if (outputDirectory == null) throw new IllegalArgumentException("输出目录不能为空");
        }
    }

    public record Result(Path queries, Path documents, Path documentMap, Path manifest, Manifest value) {}
    public record Manifest(int schemaVersion, String generator, int documentCount, String goldSha256,
                           String documentsManifestSha256, String queriesSha256,
                           String documentsSha256, String documentMapSha256) {}
    private record Gold(String queryId, String query, String documentId, String title,
                        String evidenceText, String evidenceMarker) {}
    private record SourcePaths(String pdf, String docx) {}
    private static final class MutablePaths { private String pdf; private String docx; }
}
