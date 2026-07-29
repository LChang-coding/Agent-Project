package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 独立复核格式评测数据的数量、配对、哈希、可打开性和证据标识。 */
public final class RagFormatDatasetValidator {

    private final ObjectMapper objectMapper;

    public RagFormatDatasetValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Report validate(Path root) throws IOException {
        Path manifestPath = root.resolve("manifests/dataset-manifest.json");
        RagFormatDatasetBuilder.Manifest manifest = objectMapper.readValue(
                manifestPath.toFile(), RagFormatDatasetBuilder.Manifest.class);
        List<RagFormatDatasetBuilder.DocumentManifest> documents = readDocuments(
                root.resolve(manifest.documentManifestPath()));
        List<String> failures = new ArrayList<>();
        Map<String, Set<String>> formatsBySourceDocument = new HashMap<>();
        Map<String, Integer> formatCounts = new LinkedHashMap<>();
        Map<String, Integer> complexityCounts = new LinkedHashMap<>();

        for (RagFormatDatasetBuilder.DocumentManifest document : documents) {
            Path file = root.resolve(document.relativePath()).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
                failures.add(document.formatDocumentId() + ": 文件不存在或越出数据目录");
                continue;
            }
            if (!sha256(file).equals(document.sha256())) {
                failures.add(document.formatDocumentId() + ": SHA-256不一致");
                continue;
            }
            String extracted;
            try {
                extracted = extract(file, document.format());
            } catch (Exception exception) {
                failures.add(document.formatDocumentId() + ": 无法打开，"
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                continue;
            }
            if (!extracted.contains(document.evidenceMarker())) {
                failures.add(document.formatDocumentId() + ": 提取文本缺少证据标识");
            }
            formatsBySourceDocument.computeIfAbsent(document.sourceDocumentId(), ignored -> new HashSet<>())
                    .add(document.format());
            formatCounts.merge(document.format(), 1, Integer::sum);
            complexityCounts.merge(document.format() + ":" + document.complexity(), 1, Integer::sum);
        }

        formatsBySourceDocument.forEach((documentId, formats) -> {
            if (!formats.equals(Set.of("PDF", "DOCX"))) failures.add(documentId + ": PDF/DOCX未成对");
        });
        if (formatsBySourceDocument.size() != manifest.pairedDocumentCount()) {
            failures.add("唯一源文档数不等于manifest: " + formatsBySourceDocument.size());
        }
        manifest.formatCounts().forEach((format, expected) -> {
            if (formatCounts.getOrDefault(format, 0).intValue() != expected.intValue()) {
                failures.add(format + "文件数不等于manifest");
            }
        });
        manifest.complexityCountsPerFormat().forEach((complexity, expected) -> {
            for (String format : manifest.formatCounts().keySet()) {
                if (complexityCounts.getOrDefault(format + ":" + complexity, 0).intValue()
                        != expected.intValue()) {
                    failures.add(format + ":" + complexity + "数量不等于manifest");
                }
            }
        });

        Set<String> queryIds = readJsonIds(root.resolve(manifest.queriesPath()), "_id");
        QrelSummary qrels = readQrels(root.resolve(manifest.qrelsPath()));
        Set<String> goldQueryIds = readJsonIds(root.resolve(manifest.goldPath()), "queryId");
        if (queryIds.size() != manifest.queryCount()) failures.add("问题数不等于manifest");
        if (qrels.rows() != manifest.qrelCount()) failures.add("qrels行数不等于manifest");
        if (!queryIds.equals(qrels.queryIds())) failures.add("问题与qrels的queryId不闭包");
        if (!queryIds.equals(goldQueryIds)) failures.add("问题与gold的queryId不闭包");
        if (!formatsBySourceDocument.keySet().equals(qrels.documentIds())) failures.add("文档与qrels的documentId不闭包");

        String treeHash = treeHash(root, manifestPath);
        if (!treeHash.equals(manifest.treeSha256())) failures.add("数据目录treeSha256不一致");
        return new Report(failures.isEmpty(), documents.size(), formatsBySourceDocument.size(), queryIds.size(),
                qrels.rows(), Map.copyOf(formatCounts), Map.copyOf(complexityCounts),
                manifest.treeSha256(), treeHash, List.copyOf(failures));
    }

    private List<RagFormatDatasetBuilder.DocumentManifest> readDocuments(Path path) throws IOException {
        List<RagFormatDatasetBuilder.DocumentManifest> values = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) values.add(objectMapper.readValue(
                        line, RagFormatDatasetBuilder.DocumentManifest.class));
            }
        }
        return values;
    }

    private Set<String> readJsonIds(Path path, String field) throws IOException {
        Set<String> values = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String value = objectMapper.readTree(line).path(field).asText();
                if (value.isBlank() || !values.add(value)) {
                    throw new IllegalArgumentException("JSONL ID为空或重复: " + field);
                }
            }
        }
        return values;
    }

    private QrelSummary readQrels(Path path) throws IOException {
        Set<String> queryIds = new HashSet<>();
        Set<String> documentIds = new HashSet<>();
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                if (line.isBlank()) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3 || Integer.parseInt(fields[2]) <= 0) {
                    throw new IllegalArgumentException("qrels行非法");
                }
                queryIds.add(fields[0]);
                documentIds.add(fields[1]);
                rows++;
            }
        }
        return new QrelSummary(queryIds, documentIds, rows);
    }

    private String extract(Path path, String format) throws IOException {
        if ("PDF".equals(format)) {
            try (var document = Loader.loadPDF(path.toFile())) {
                return new PDFTextStripper().getText(document);
            }
        }
        if ("DOCX".equals(format)) {
            StringBuilder text = new StringBuilder();
            try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input)) {
                document.getHeaderList().forEach(header -> text.append(header.getText()).append('\n'));
                document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append('\n'));
                document.getTables().forEach(table -> table.getRows().forEach(row ->
                        row.getTableCells().forEach(cell -> text.append(cell.getText()).append('\n'))));
                document.getFooterList().forEach(footer -> text.append(footer.getText()).append('\n'));
            }
            return text.toString();
        }
        throw new IllegalArgumentException("未知格式: " + format);
    }

    private String treeHash(Path root, Path excluded) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(value -> !value.equals(excluded)).sorted().toList()) {
                digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
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

    public record Report(boolean valid, int formatDocumentCount, int pairedDocumentCount, int queryCount,
                         int qrelCount, Map<String, Integer> formatCounts,
                         Map<String, Integer> complexityCounts, String expectedTreeSha256,
                         String actualTreeSha256, List<String> failures) {}

    private record QrelSummary(Set<String> queryIds, Set<String> documentIds, int rows) {}
}
