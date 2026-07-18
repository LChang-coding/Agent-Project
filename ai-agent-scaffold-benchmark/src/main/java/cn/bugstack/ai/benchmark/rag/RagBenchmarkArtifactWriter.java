package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将内存数据集转为可上传 Markdown 分片和带哈希的运行清单。 */
public final class RagBenchmarkArtifactWriter {

    private static final String DOCUMENT_MARKER = "BENCH_DOC_B64_";
    private final ObjectMapper objectMapper;

    public RagBenchmarkArtifactWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Manifest write(RagBenchmarkDataset dataset, Path output, Configuration configuration,
                          SourceFiles sourceFiles) throws IOException {
        if (dataset == null || configuration == null || sourceFiles == null) {
            throw new IllegalArgumentException("数据集、输出配置和来源文件不能为空");
        }
        prepareEmptyDirectory(output);
        Path documentsDirectory = Files.createDirectory(output.resolve("documents"));
        List<DocumentMapping> mappings = writeMarkdownShards(dataset, documentsDirectory,
                configuration.shardMaxBytes());
        writeJsonLines(output.resolve("document-map.jsonl"), mappings);
        writeQueries(output.resolve("queries.jsonl"), dataset.queries());
        writeQrels(output.resolve("qrels.tsv"), dataset.qrels());

        List<Artifact> sources = List.of(artifact("corpus", sourceFiles.corpus()),
                artifact("queries", sourceFiles.queries()), artifact("qrels", sourceFiles.qrels()));
        List<Artifact> generated = new ArrayList<>();
        try (var paths = Files.walk(output)) {
            paths.filter(Files::isRegularFile).sorted().forEach(path -> {
                try {
                    generated.add(artifact(output.relativize(path).toString(), path));
                } catch (IOException exception) {
                    throw new ArtifactWriteException(exception);
                }
            });
        } catch (ArtifactWriteException exception) {
            throw exception.ioException;
        }
        Manifest manifest = new Manifest(1, configuration.datasetName(), configuration.sourceUrl(),
                configuration.sourceRevision(), configuration.license(), configuration.subsetStrategy(),
                configuration.seed(), dataset.documents().size(), dataset.queries().size(),
                dataset.qrels().values().stream().mapToInt(Map::size).sum(), configuration.shardMaxBytes(),
                Instant.now().toString(), sources, List.copyOf(generated));
        objectMapper.writeValue(output.resolve("manifest.json").toFile(), manifest);
        return manifest;
    }

    private List<DocumentMapping> writeMarkdownShards(RagBenchmarkDataset dataset, Path directory,
                                                       int shardMaxBytes) throws IOException {
        List<DocumentMapping> mappings = new ArrayList<>();
        int shardIndex = 0;
        BufferedWriter writer = null;
        String shardName = null;
        int shardBytes = 0;
        try {
            for (RagBenchmarkDataset.Document document : dataset.documents().values().stream()
                    .sorted(java.util.Comparator.comparing(RagBenchmarkDataset.Document::id)).toList()) {
                String heading = marker(document.id()) + titleSuffix(document.title());
                String markdown = "# " + heading + "\n\n" + escapeMarkdownHeadings(document.text()) + "\n\n";
                int documentBytes = markdown.getBytes(StandardCharsets.UTF_8).length;
                if (documentBytes > shardMaxBytes) {
                    throw new IllegalArgumentException("单篇文档超过Markdown分片上限: " + document.id());
                }
                if (writer == null || shardBytes + documentBytes > shardMaxBytes) {
                    if (writer != null) writer.close();
                    shardName = "benchmark-%04d.md".formatted(++shardIndex);
                    writer = Files.newBufferedWriter(directory.resolve(shardName), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    shardBytes = 0;
                }
                writer.write(markdown);
                shardBytes += documentBytes;
                mappings.add(new DocumentMapping(document.id(), shardName, marker(document.id()),
                        sha256(document.text().getBytes(StandardCharsets.UTF_8))));
            }
        } finally {
            if (writer != null) writer.close();
        }
        return List.copyOf(mappings);
    }

    private void writeQueries(Path path, Map<String, String> queries) throws IOException {
        List<Map<String, String>> values = new ArrayList<>();
        queries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.add(Map.of("queryId", entry.getKey(), "text", entry.getValue())));
        writeJsonLines(path, values);
    }

    private void writeQrels(Path path, Map<String, Map<String, Integer>> qrels) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("query-id\tcorpus-id\tscore\n");
            for (String queryId : qrels.keySet().stream().sorted().toList()) {
                for (Map.Entry<String, Integer> value : qrels.get(queryId).entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    writer.write(queryId + "\t" + value.getKey() + "\t" + value.getValue() + "\n");
                }
            }
        }
    }

    private void writeJsonLines(Path path, List<?> values) throws IOException {
        ObjectMapper compact = objectMapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (Object value : values) {
                writer.write(compact.writeValueAsString(value));
                writer.newLine();
            }
        }
    }

    private void prepareEmptyDirectory(Path output) throws IOException {
        if (output == null) throw new IllegalArgumentException("输出目录不能为空");
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) throw new IllegalArgumentException("输出路径不是目录");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(output)) {
                if (stream.iterator().hasNext()) throw new IllegalArgumentException("输出目录必须为空，禁止覆盖既有评测产物");
            }
        } else {
            Files.createDirectories(output);
        }
    }

    public static String marker(String documentId) {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("文档ID不能为空");
        return DOCUMENT_MARKER + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(documentId.getBytes(StandardCharsets.UTF_8));
    }

    public static String documentIdFromHeading(String headingPath) {
        if (headingPath == null) return null;
        int start = headingPath.indexOf(DOCUMENT_MARKER);
        if (start < 0) return null;
        start += DOCUMENT_MARKER.length();
        int end = start;
        while (end < headingPath.length()) {
            char value = headingPath.charAt(end);
            if (!(Character.isLetterOrDigit(value) || value == '-' || value == '_')) break;
            end++;
        }
        if (end == start) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(headingPath.substring(start, end)),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String titleSuffix(String title) {
        if (title == null || title.isBlank()) return "";
        String safe = title.replace('\r', ' ').replace('\n', ' ').replace('#', ' ').strip();
        return safe.isBlank() ? "" : " — " + safe;
    }

    private String escapeMarkdownHeadings(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.matches("^#{1,6}\\s+.*")) result.append('\\');
            result.append(line).append('\n');
        }
        return result.toString().stripTrailing();
    }

    private Artifact artifact(String name, Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("来源文件不存在: " + path);
        return new Artifact(name, path.toAbsolutePath().normalize().toString(), Files.size(path), sha256(path));
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] value) { return HexFormat.of().formatHex(digest().digest(value)); }
    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    public record Configuration(String datasetName, String sourceUrl, String sourceRevision, String license,
                                String subsetStrategy, long seed, int shardMaxBytes) {
        public Configuration {
            requireText(datasetName, "数据集名称");
            requireText(sourceUrl, "来源URL");
            requireText(sourceRevision, "来源revision");
            requireText(license, "许可");
            requireText(subsetStrategy, "子集策略");
            if (shardMaxBytes < 4096) throw new IllegalArgumentException("分片字节上限过小");
        }
    }
    public record SourceFiles(Path corpus, Path queries, Path qrels) {}
    public record DocumentMapping(String documentId, String shardFile, String headingMarker, String contentSha256) {}
    public record Artifact(String name, String path, long sizeBytes, String sha256) {}
    public record Manifest(int schemaVersion, String datasetName, String sourceUrl, String sourceRevision,
                           String license, String subsetStrategy, long seed, int documentCount, int queryCount,
                           int qrelCount, int shardMaxBytes, String generatedAt,
                           List<Artifact> sourceFiles, List<Artifact> generatedFiles) {}

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    private static final class ArtifactWriteException extends RuntimeException {
        private final IOException ioException;
        private ArtifactWriteException(IOException ioException) { this.ioException = ioException; }
    }
}
