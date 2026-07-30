package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从已冻结的 PDF/DOCX 配对数据集中生成可复核的分层子集。 */
public final class RagFormatDatasetSubsetBuilder {

    private final ObjectMapper objectMapper;

    public RagFormatDatasetSubsetBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result build(Configuration configuration) throws IOException {
        validate(configuration);
        Path source = configuration.sourceDirectory().toAbsolutePath().normalize();
        Path output = configuration.outputDirectory().toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("子集输出目录必须不存在");

        Path sourceManifestPath = source.resolve("manifests/dataset-manifest.json");
        RagFormatDatasetBuilder.Manifest parent = objectMapper.readValue(
                sourceManifestPath.toFile(), RagFormatDatasetBuilder.Manifest.class);
        if (!treeHash(source, sourceManifestPath).equals(parent.treeSha256())) {
            throw new IllegalArgumentException("源格式数据集treeSha256不一致");
        }
        List<RagFormatDatasetBuilder.DocumentManifest> sourceDocuments = readDocuments(
                source.resolve(parent.documentManifestPath()));
        Map<String, List<RagFormatDatasetBuilder.DocumentManifest>> documentsBySource =
                validateAndGroup(sourceDocuments);
        Set<String> selectedSources = select(documentsBySource, configuration);
        List<RagFormatDatasetBuilder.DocumentManifest> selectedDocuments = sourceDocuments.stream()
                .filter(value -> selectedSources.contains(value.sourceDocumentId())).toList();
        Set<String> selectedQueries = selectedDocuments.stream()
                .filter(value -> "PDF".equals(value.format())).map(RagFormatDatasetBuilder.DocumentManifest::queryId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Path manifestDirectory = output.resolve("manifests");
        Path goldDirectory = output.resolve("gold");
        Files.createDirectories(manifestDirectory);
        Files.createDirectories(goldDirectory);
        copyDocuments(source, output, selectedDocuments);
        copyLicenses(source.resolve("licenses"), output.resolve("licenses"));

        writeJsonLines(manifestDirectory.resolve("documents.jsonl"), selectedDocuments);
        filterJsonLines(source.resolve(parent.queriesPath()), goldDirectory.resolve("queries.jsonl"),
                "_id", selectedQueries);
        filterJsonLines(source.resolve(parent.goldPath()), goldDirectory.resolve("gold.jsonl"),
                "queryId", selectedQueries);
        int qrelCount = filterQrels(source.resolve(parent.qrelsPath()), goldDirectory.resolve("qrels.tsv"),
                selectedQueries, selectedSources);
        if (selectedQueries.size() != selectedSources.size() || qrelCount != selectedSources.size()) {
            throw new IllegalArgumentException("子集问题、文档和qrels未一一闭包");
        }

        Map<String, Integer> complexityCounts = new LinkedHashMap<>();
        complexityCounts.put("SIMPLE", configuration.simpleCount());
        complexityCounts.put("MEDIUM", configuration.mediumCount());
        complexityCounts.put("COMPLEX", configuration.complexCount());
        Map<String, Integer> formatCounts = new LinkedHashMap<>();
        formatCounts.put("PDF", selectedSources.size());
        formatCounts.put("DOCX", selectedSources.size());
        RagFormatDatasetBuilder.Manifest manifest = new RagFormatDatasetBuilder.Manifest(
                1, configuration.datasetName(),
                parent.derivation() + "+stratified-subset-v1(parentTree=" + parent.treeSha256() + ")",
                configuration.seed(), parent.generatedAt(), parent.sourceUrl(), parent.sourceRevision(),
                parent.license(), selectedSources.size(), selectedQueries.size(), qrelCount,
                complexityCounts, formatCounts, parent.corpusSha256(),
                sha256(goldDirectory.resolve("queries.jsonl")), sha256(goldDirectory.resolve("qrels.tsv")),
                "gold/queries.jsonl", "gold/qrels.tsv", "gold/gold.jsonl",
                "manifests/documents.jsonl", "");
        Path manifestPath = manifestDirectory.resolve("dataset-manifest.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        RagFormatDatasetBuilder.Manifest completed = new RagFormatDatasetBuilder.Manifest(
                manifest.schemaVersion(), manifest.datasetName(), manifest.derivation(), manifest.seed(),
                manifest.generatedAt(), manifest.sourceUrl(), manifest.sourceRevision(), manifest.license(),
                manifest.pairedDocumentCount(), manifest.queryCount(), manifest.qrelCount(),
                manifest.complexityCountsPerFormat(), manifest.formatCounts(), manifest.corpusSha256(),
                manifest.queriesSha256(), manifest.qrelsSha256(), manifest.queriesPath(), manifest.qrelsPath(),
                manifest.goldPath(), manifest.documentManifestPath(), treeHash(output, manifestPath));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), completed);
        return new Result(completed, List.copyOf(selectedSources));
    }

    private Map<String, List<RagFormatDatasetBuilder.DocumentManifest>> validateAndGroup(
            List<RagFormatDatasetBuilder.DocumentManifest> documents) {
        Map<String, List<RagFormatDatasetBuilder.DocumentManifest>> grouped = new LinkedHashMap<>();
        Set<String> formatIds = new LinkedHashSet<>();
        for (RagFormatDatasetBuilder.DocumentManifest document : documents) {
            if (!formatIds.add(document.formatDocumentId())) {
                throw new IllegalArgumentException("源数据存在重复formatDocumentId");
            }
            grouped.computeIfAbsent(document.sourceDocumentId(), ignored -> new ArrayList<>()).add(document);
        }
        grouped.forEach((sourceId, values) -> {
            Set<String> formats = values.stream().map(RagFormatDatasetBuilder.DocumentManifest::format)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> queries = values.stream().map(RagFormatDatasetBuilder.DocumentManifest::queryId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> complexities = values.stream().map(RagFormatDatasetBuilder.DocumentManifest::complexity)
                    .collect(java.util.stream.Collectors.toSet());
            if (values.size() != 2 || !formats.equals(Set.of("PDF", "DOCX"))
                    || queries.size() != 1 || complexities.size() != 1) {
                throw new IllegalArgumentException("源文档未形成一致的PDF/DOCX配对: " + sourceId);
            }
        });
        return grouped;
    }

    private Set<String> select(Map<String, List<RagFormatDatasetBuilder.DocumentManifest>> documentsBySource,
                               Configuration configuration) {
        Map<Complexity, Integer> quotas = new EnumMap<>(Complexity.class);
        quotas.put(Complexity.SIMPLE, configuration.simpleCount());
        quotas.put(Complexity.MEDIUM, configuration.mediumCount());
        quotas.put(Complexity.COMPLEX, configuration.complexCount());
        Set<String> selected = new LinkedHashSet<>();
        for (Map.Entry<Complexity, Integer> quota : quotas.entrySet()) {
            List<String> candidates = documentsBySource.entrySet().stream()
                    .filter(entry -> quota.getKey().name().equals(entry.getValue().get(0).complexity()))
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(value -> stableHash(configuration.seed(), value)))
                    .toList();
            if (candidates.size() < quota.getValue()) {
                throw new IllegalArgumentException(quota.getKey() + "文档不足，期望=" + quota.getValue()
                        + "，实际=" + candidates.size());
            }
            selected.addAll(candidates.subList(0, quota.getValue()));
        }
        return selected;
    }

    private List<RagFormatDatasetBuilder.DocumentManifest> readDocuments(Path path) throws IOException {
        List<RagFormatDatasetBuilder.DocumentManifest> values = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    values.add(objectMapper.readValue(line, RagFormatDatasetBuilder.DocumentManifest.class));
                }
            }
        }
        return values;
    }

    private void copyDocuments(Path source, Path output,
                               List<RagFormatDatasetBuilder.DocumentManifest> documents) throws IOException {
        for (RagFormatDatasetBuilder.DocumentManifest document : documents) {
            Path sourceFile = source.resolve(document.relativePath()).normalize();
            if (!sourceFile.startsWith(source) || !Files.isRegularFile(sourceFile)
                    || !sha256(sourceFile).equals(document.sha256())) {
                throw new IllegalArgumentException("源文档缺失或SHA不一致: " + document.formatDocumentId());
            }
            Path target = output.resolve(document.relativePath()).normalize();
            if (!target.startsWith(output)) throw new IllegalArgumentException("目标文档路径越界");
            Files.createDirectories(target.getParent());
            Files.copy(sourceFile, target);
        }
    }

    private void copyLicenses(Path source, Path output) throws IOException {
        if (!Files.isDirectory(source)) throw new IllegalArgumentException("源数据缺少licenses目录");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path target = output.resolve(source.relativize(path).toString()).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }
    }

    private void writeJsonLines(Path path, List<?> values) throws IOException {
        List<String> lines = new ArrayList<>(values.size());
        for (Object value : values) lines.add(objectMapper.writeValueAsString(value));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private void filterJsonLines(Path source, Path output, String idField, Set<String> selected)
            throws IOException {
        List<String> lines = new ArrayList<>();
        Set<String> found = new LinkedHashSet<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode value = objectMapper.readTree(line);
            String id = value.path(idField).asText();
            if (selected.contains(id)) {
                if (!found.add(id)) throw new IllegalArgumentException("JSONL存在重复ID: " + id);
                lines.add(line);
            }
        }
        if (!found.equals(selected)) throw new IllegalArgumentException("JSONL未覆盖全部选中ID: " + idField);
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private int filterQrels(Path source, Path output, Set<String> selectedQueries, Set<String> selectedSources)
            throws IOException {
        List<String> sourceLines = Files.readAllLines(source, StandardCharsets.UTF_8);
        if (sourceLines.isEmpty()) throw new IllegalArgumentException("qrels为空");
        List<String> selected = new ArrayList<>();
        selected.add(sourceLines.get(0));
        Set<String> pairs = new LinkedHashSet<>();
        for (String line : sourceLines.subList(1, sourceLines.size())) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("qrels行非法");
            if (selectedQueries.contains(fields[0]) && selectedSources.contains(fields[1])) {
                if (!pairs.add(fields[0] + "\0" + fields[1])) {
                    throw new IllegalArgumentException("qrels存在重复关系");
                }
                selected.add(line);
            }
        }
        Files.write(output, selected, StandardCharsets.UTF_8);
        return pairs.size();
    }

    private String stableHash(long seed, String sourceDocumentId) {
        return HexFormat.of().formatHex(sha256Digest().digest(
                (seed + ":" + sourceDocumentId).getBytes(StandardCharsets.UTF_8)));
    }

    private String treeHash(Path root, Path excluded) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(value -> !value.equals(excluded)).sorted().toList()) {
                digest.update(root.relativize(path).toString().replace('\\', '/')
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(Path path) throws IOException {
        return HexFormat.of().formatHex(sha256Digest().digest(Files.readAllBytes(path)));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private void validate(Configuration value) {
        if (value == null || value.sourceDirectory() == null || value.outputDirectory() == null
                || value.datasetName() == null || value.datasetName().isBlank()) {
            throw new IllegalArgumentException("格式子集配置不能为空");
        }
        if (value.simpleCount() < 1 || value.mediumCount() < 1 || value.complexCount() < 1) {
            throw new IllegalArgumentException("三档复杂度配额必须为正整数");
        }
    }

    public record Configuration(String datasetName, Path sourceDirectory, Path outputDirectory, long seed,
                                int simpleCount, int mediumCount, int complexCount) {}

    public record Result(RagFormatDatasetBuilder.Manifest manifest, List<String> selectedSourceDocumentIds) {}

    private enum Complexity { SIMPLE, MEDIUM, COMPLEX }
}
