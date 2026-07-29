package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 把同一批 SciFact 金标文档确定性渲染成 PDF 与 DOCX，用于隔离格式和版面复杂度的影响。
 */
public final class RagFormatDatasetBuilder {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-29T00:00:00Z");
    private static final String DERIVATION = "scifact-paired-format-layout-v1";
    private static final int SIMPLE_COUNT = 80;
    private static final int MEDIUM_COUNT = 70;
    private static final int COMPLEX_COUNT = 50;

    private final ObjectMapper objectMapper;

    public RagFormatDatasetBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    public Manifest build(Configuration configuration) throws IOException {
        validate(configuration);
        if (Files.exists(configuration.outputDirectory())) {
            throw new IllegalArgumentException("禁止覆盖既有格式评测数据目录: " + configuration.outputDirectory());
        }
        RagBenchmarkDataset source = new BeirDatasetLoader(objectMapper).load(
                configuration.corpus(), configuration.queries(), configuration.qrels(),
                BeirDatasetLoader.Limits.defaults());
        List<SelectedCase> selected = selectExactly200(source, configuration.seed());

        Path output = configuration.outputDirectory();
        Path pdfDirectory = output.resolve("prepared/pdf");
        Path docxDirectory = output.resolve("prepared/docx");
        Path manifestDirectory = output.resolve("manifests");
        Path goldDirectory = output.resolve("gold");
        Path licenseDirectory = output.resolve("licenses");
        Files.createDirectories(pdfDirectory);
        Files.createDirectories(docxDirectory);
        Files.createDirectories(manifestDirectory);
        Files.createDirectories(goldDirectory);
        Files.createDirectories(licenseDirectory);

        List<DocumentManifest> documents = new ArrayList<>(selected.size() * 2);
        for (int index = 0; index < selected.size(); index++) {
            SelectedCase selectedCase = selected.get(index);
            Complexity complexity = complexity(index);
            String evidenceMarker = evidenceMarker(selectedCase.document().id());
            Path pdf = pdfDirectory.resolve(fileName(index, selectedCase.document().id(), "pdf"));
            Path docx = docxDirectory.resolve(fileName(index, selectedCase.document().id(), "docx"));
            writePdf(pdf, selectedCase.document(), evidenceMarker, complexity);
            writeDocx(docx, selectedCase.document(), evidenceMarker, complexity);
            documents.add(documentManifest(output, selectedCase, complexity, evidenceMarker, "PDF", pdf));
            documents.add(documentManifest(output, selectedCase, complexity, evidenceMarker, "DOCX", docx));
        }

        writeQueries(goldDirectory.resolve("queries.jsonl"), selected);
        writeQrels(goldDirectory.resolve("qrels.tsv"), selected);
        writeGold(goldDirectory.resolve("gold.jsonl"), selected);
        writeJsonLines(manifestDirectory.resolve("documents.jsonl"), documents);
        writeSourceNotice(licenseDirectory.resolve("SOURCE-AND-LICENSE.md"), configuration);

        Manifest manifest = new Manifest(1, configuration.datasetName(), DERIVATION, configuration.seed(),
                FIXED_TIME.toString(), configuration.sourceUrl(), configuration.sourceRevision(),
                configuration.license(), selected.size(), 200, 200,
                Map.of("SIMPLE", SIMPLE_COUNT, "MEDIUM", MEDIUM_COUNT, "COMPLEX", COMPLEX_COUNT),
                Map.of("PDF", 200, "DOCX", 200),
                sha256(configuration.corpus()), sha256(configuration.queries()), sha256(configuration.qrels()),
                relative(output, goldDirectory.resolve("queries.jsonl")),
                relative(output, goldDirectory.resolve("qrels.tsv")),
                relative(output, goldDirectory.resolve("gold.jsonl")),
                relative(output, manifestDirectory.resolve("documents.jsonl")));
        Path manifestPath = manifestDirectory.resolve("dataset-manifest.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        String treeHash = treeHash(output, manifestPath);
        Manifest completed = manifest.withTreeSha256(treeHash);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), completed);
        return completed;
    }

    private List<SelectedCase> selectExactly200(RagBenchmarkDataset source, long seed) {
        List<SelectedCase> candidates = new ArrayList<>();
        Set<String> claimedDocuments = new LinkedHashSet<>();
        source.qrels().entrySet().stream()
                .filter(entry -> positiveDocumentIds(entry.getValue()).size() == 1)
                .sorted(Comparator.comparing(entry -> stableHash(seed, entry.getKey())))
                .forEach(entry -> {
                    String documentId = positiveDocumentIds(entry.getValue()).get(0);
                    if (claimedDocuments.add(documentId)) {
                        candidates.add(new SelectedCase(entry.getKey(), source.queries().get(entry.getKey()),
                                source.documents().get(documentId), entry.getValue().get(documentId)));
                    }
                });
        if (candidates.size() < 200) {
            throw new IllegalArgumentException("至少需要200个由单金标问题唯一覆盖的文档，实际=" + candidates.size());
        }
        return List.copyOf(candidates.subList(0, 200));
    }

    private List<String> positiveDocumentIds(Map<String, Integer> qrels) {
        return qrels.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey).sorted().toList();
    }

    private void writeQueries(Path path, List<SelectedCase> selected) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (SelectedCase value : selected) {
                Map<String, String> record = new LinkedHashMap<>();
                record.put("_id", value.queryId());
                record.put("text", value.query());
                writer.write(objectMapper.writeValueAsString(record));
                writer.newLine();
            }
        }
    }

    private void writeQrels(Path path, List<SelectedCase> selected) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("query-id\tcorpus-id\tscore");
            writer.newLine();
            for (SelectedCase value : selected) {
                writer.write(value.queryId() + "\t" + value.document().id() + "\t" + value.relevance());
                writer.newLine();
            }
        }
    }

    private void writeGold(Path path, List<SelectedCase> selected) throws IOException {
        List<GoldRecord> records = selected.stream().map(value -> new GoldRecord(value.queryId(), value.query(),
                value.document().id(), value.relevance(), value.document().title(), value.document().text(),
                evidenceMarker(value.document().id()), "retrieval_evidence_only_no_gold_generated_answer")).toList();
        writeJsonLines(path, records);
    }

    private void writeJsonLines(Path path, List<?> values) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Object value : values) {
                writer.write(objectMapper.writeValueAsString(value));
                writer.newLine();
            }
        }
    }

    private void writeSourceNotice(Path path, Configuration configuration) throws IOException {
        String content = """
                # 数据来源与许可证

                - 数据集：SciFact（BEIR 分发快照）
                - 来源：%s
                - 来源版本：%s
                - 许可证：%s
                - 派生算法：%s
                - 说明：PDF/DOCX 是由同一批 SciFact 文本和金标确定性生成的受控版面压力集，不是原生采集的真实办公文档。
                - 限制：该数据集只评估检索证据，不提供生成式答案金标，因此不得报告答案正确率或忠实度。
                """.formatted(configuration.sourceUrl(), configuration.sourceRevision(), configuration.license(), DERIVATION);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private DocumentManifest documentManifest(Path root, SelectedCase selected, Complexity complexity,
                                              String marker, String format, Path path) throws IOException {
        return new DocumentManifest(format + ":" + selected.document().id(), selected.document().id(),
                selected.queryId(), format, complexity.name(), relative(root, path), Files.size(path),
                sha256(path), marker, selected.document().text().length());
    }

    private void writeDocx(Path path, RagBenchmarkDataset.Document document, String marker,
                           Complexity complexity) throws IOException {
        try (XWPFDocument docx = new XWPFDocument()) {
            docx.getProperties().getCoreProperties().setTitle(document.title());
            docx.getProperties().getCoreProperties().setCreator("ai-agent-scaffold-benchmark");
            docx.getProperties().getCoreProperties().setCreated(
                    java.util.Optional.of(java.util.Date.from(FIXED_TIME)));
            if (complexity != Complexity.SIMPLE) {
                XWPFHeader header = docx.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
                header.createParagraph().createRun().setText("SciFact controlled format benchmark");
                XWPFFooter footer = docx.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
                footer.createParagraph().createRun().setText("Document " + document.id());
            }
            addDocxHeading(docx, document.title().isBlank() ? "Scientific evidence" : document.title(), 1);
            addDocxParagraph(docx, marker, true);
            if (complexity == Complexity.SIMPLE) {
                addDocxParagraph(docx, document.text(), false);
            } else {
                addDocxHeading(docx, "Evidence abstract", 2);
                List<String> sentences = sentences(document.text());
                for (String sentence : sentences) addDocxParagraph(docx, sentence, false);
                addDocxHeading(docx, "Document metadata", 2);
                XWPFTable table = docx.createTable(2, 2);
                table.getRow(0).getCell(0).setText("Corpus");
                table.getRow(0).getCell(1).setText("SciFact");
                table.getRow(1).getCell(0).setText("Document ID");
                table.getRow(1).getCell(1).setText(document.id());
                if (complexity == Complexity.COMPLEX) {
                    addDocxHeading(docx, "Evidence checklist", 2);
                    for (int index = 0; index < sentences.size(); index++) {
                        XWPFParagraph paragraph = docx.createParagraph();
                        paragraph.setStyle("ListBullet");
                        paragraph.createRun().setText("Evidence segment " + (index + 1) + ": " + sentences.get(index));
                    }
                    XWPFParagraph pageBreak = docx.createParagraph();
                    pageBreak.createRun().addBreak(BreakType.PAGE);
                    addDocxHeading(docx, "Source continuity", 2);
                    addDocxParagraph(docx, "The canonical evidence above remains the authoritative retrieval target.", false);
                }
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            docx.write(raw);
            normalizeZip(raw.toByteArray(), path);
        }
    }

    private void normalizeZip(byte[] source, Path path) throws IOException {
        FileTime fixed = FileTime.from(FIXED_TIME);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source));
             OutputStream file = Files.newOutputStream(path);
             ZipOutputStream output = new ZipOutputStream(file)) {
            output.setLevel(9);
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry normalized = new ZipEntry(entry.getName());
                normalized.setTime(FIXED_TIME.toEpochMilli());
                normalized.setCreationTime(fixed);
                normalized.setLastAccessTime(fixed);
                normalized.setLastModifiedTime(fixed);
                normalized.setMethod(ZipEntry.DEFLATED);
                output.putNextEntry(normalized);
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) output.write(buffer, 0, read);
                }
                output.closeEntry();
            }
        }
    }

    private void addDocxHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(level == 1 ? 18 : 14);
        run.setText(text);
    }

    private void addDocxParagraph(XWPFDocument document, String text, boolean monospace) {
        XWPFRun run = document.createParagraph().createRun();
        run.setFontFamily(monospace ? "Courier New" : "Arial");
        run.setFontSize(monospace ? 9 : 11);
        run.setText(text);
    }

    private void writePdf(Path path, RagBenchmarkDataset.Document document, String marker,
                          Complexity complexity) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle(document.title());
            information.setAuthor("ai-agent-scaffold-benchmark");
            information.setProducer(DERIVATION);
            GregorianCalendar fixed = GregorianCalendar.from(
                    FIXED_TIME.atZone(java.time.ZoneOffset.UTC));
            fixed.setTimeZone(TimeZone.getTimeZone("UTC"));
            information.setCreationDate(fixed);
            information.setModificationDate(fixed);
            pdf.setDocumentInformation(information);
            COSArray id = new COSArray();
            byte[] deterministicId = digest((document.id() + "\0" + complexity).getBytes(StandardCharsets.UTF_8));
            id.add(new COSString(deterministicId));
            id.add(new COSString(deterministicId));
            pdf.getDocument().getTrailer().setItem(COSName.ID, id);

            List<TextBlock> blocks = pdfBlocks(document, marker, complexity);
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(pdf, page);
            float y = 790;
            if (complexity != Complexity.SIMPLE) {
                y = writePdfLine(stream, "SciFact controlled format benchmark", 9, 50, y);
                y -= 8;
            }
            for (TextBlock block : blocks) {
                float fontSize = block.heading() ? 15 : 10;
                for (String line : wrap(block.text(), block.heading() ? 72 : 92)) {
                    if (y < 65) {
                        if (complexity != Complexity.SIMPLE) {
                            writePdfLine(stream, "Document " + document.id(), 8, 50, 40);
                        }
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        pdf.addPage(page);
                        stream = new PDPageContentStream(pdf, page);
                        y = 790;
                    }
                    y = writePdfLine(stream, line, fontSize, 50, y);
                }
                y -= block.heading() ? 8 : 4;
            }
            if (complexity != Complexity.SIMPLE) {
                writePdfLine(stream, "Document " + document.id(), 8, 50, 40);
            }
            stream.close();
            pdf.save(path.toFile());
        }
    }

    private List<TextBlock> pdfBlocks(RagBenchmarkDataset.Document document, String marker, Complexity complexity) {
        List<TextBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(document.title().isBlank() ? "Scientific evidence" : document.title(), true));
        blocks.add(new TextBlock(marker, false));
        if (complexity == Complexity.SIMPLE) {
            blocks.add(new TextBlock(document.text(), false));
            return blocks;
        }
        blocks.add(new TextBlock("Evidence abstract", true));
        sentences(document.text()).forEach(sentence -> blocks.add(new TextBlock(sentence, false)));
        blocks.add(new TextBlock("Document metadata", true));
        blocks.add(new TextBlock("Corpus | SciFact", false));
        blocks.add(new TextBlock("Document ID | " + document.id(), false));
        if (complexity == Complexity.COMPLEX) {
            blocks.add(new TextBlock("Evidence checklist", true));
            int index = 1;
            for (String sentence : sentences(document.text())) {
                blocks.add(new TextBlock(index++ + ". " + sentence, false));
            }
            blocks.add(new TextBlock("Source continuity", true));
            blocks.add(new TextBlock("The canonical evidence above remains the authoritative retrieval target.", false));
        }
        return blocks;
    }

    private float writePdfLine(PDPageContentStream stream, String text, float size, float x, float y)
            throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(asWinAnsi(text));
        stream.endText();
        return y - size - 3;
    }

    private String asWinAnsi(String value) {
        return value.replace('\u2013', '-').replace('\u2014', '-').replace('\u2018', '\'')
                .replace('\u2019', '\'').replace('\u201c', '"').replace('\u201d', '"')
                .replaceAll("[^\\x20-\\x7E]", " ");
    }

    private List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : asWinAnsi(text).split("\\R", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isBlank()) continue;
                if (!current.isEmpty() && current.length() + 1 + word.length() > maxChars) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) current.append(' ');
                current.append(word);
            }
            if (!current.isEmpty()) lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of(" ") : lines;
    }

    private List<String> sentences(String text) {
        List<String> result = java.util.Arrays.stream(text.split("(?<=[.!?])\\s+"))
                .map(String::strip).filter(value -> !value.isBlank()).toList();
        return result.isEmpty() ? List.of(text) : result;
    }

    private Complexity complexity(int index) {
        if (index < SIMPLE_COUNT) return Complexity.SIMPLE;
        if (index < SIMPLE_COUNT + MEDIUM_COUNT) return Complexity.MEDIUM;
        return Complexity.COMPLEX;
    }

    private String fileName(int index, String documentId, String extension) {
        return "%03d-scifact-%s.%s".formatted(index + 1,
                documentId.replaceAll("[^A-Za-z0-9._-]", "_"), extension);
    }

    private String evidenceMarker(String documentId) {
        return "SCIFACT-EVIDENCE-" + documentId;
    }

    private String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private String stableHash(long seed, String value) {
        return HexFormat.of().formatHex(digest((seed + "\0" + value).getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(Path path) throws IOException {
        return HexFormat.of().formatHex(digest(Files.readAllBytes(path)));
    }

    private String treeHash(Path root, Path excluded) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(value -> !value.equals(excluded)).sorted().toList()) {
                digest.update(relative(root, path).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] digest(byte[] value) {
        return sha256Digest().digest(value);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private void validate(Configuration value) {
        if (value == null || value.outputDirectory() == null || value.corpus() == null
                || value.queries() == null || value.qrels() == null) {
            throw new IllegalArgumentException("格式评测数据配置不能为空");
        }
        if (value.datasetName() == null || value.datasetName().isBlank()
                || value.sourceUrl() == null || value.sourceUrl().isBlank()
                || value.sourceRevision() == null || value.sourceRevision().isBlank()
                || value.license() == null || value.license().isBlank()) {
            throw new IllegalArgumentException("数据集名称、来源、版本和许可证不能为空");
        }
    }

    public record Configuration(String datasetName, Path corpus, Path queries, Path qrels, Path outputDirectory,
                                String sourceUrl, String sourceRevision, String license, long seed) {}

    public record Manifest(int schemaVersion, String datasetName, String derivation, long seed,
                           String generatedAt, String sourceUrl, String sourceRevision, String license,
                           int pairedDocumentCount, int queryCount, int qrelCount,
                           Map<String, Integer> complexityCountsPerFormat,
                           Map<String, Integer> formatCounts, String corpusSha256, String queriesSha256,
                           String qrelsSha256, String queriesPath, String qrelsPath, String goldPath,
                           String documentManifestPath, String treeSha256) {
        private Manifest(int schemaVersion, String datasetName, String derivation, long seed,
                         String generatedAt, String sourceUrl, String sourceRevision, String license,
                         int pairedDocumentCount, int queryCount, int qrelCount,
                         Map<String, Integer> complexityCountsPerFormat, Map<String, Integer> formatCounts,
                         String corpusSha256, String queriesSha256, String qrelsSha256, String queriesPath,
                         String qrelsPath, String goldPath, String documentManifestPath) {
            this(schemaVersion, datasetName, derivation, seed, generatedAt, sourceUrl, sourceRevision, license,
                    pairedDocumentCount, queryCount, qrelCount, complexityCountsPerFormat, formatCounts,
                    corpusSha256, queriesSha256, qrelsSha256, queriesPath, qrelsPath, goldPath,
                    documentManifestPath, "");
        }

        private Manifest withTreeSha256(String value) {
            return new Manifest(schemaVersion, datasetName, derivation, seed, generatedAt, sourceUrl,
                    sourceRevision, license, pairedDocumentCount, queryCount, qrelCount,
                    complexityCountsPerFormat, formatCounts, corpusSha256, queriesSha256, qrelsSha256,
                    queriesPath, qrelsPath, goldPath, documentManifestPath, value);
        }
    }

    public record DocumentManifest(String formatDocumentId, String sourceDocumentId, String queryId,
                                   String format, String complexity, String relativePath, long bytes,
                                   String sha256, String evidenceMarker, int canonicalTextChars) {}

    public record GoldRecord(String queryId, String query, String documentId, int relevance, String title,
                             String evidenceText, String evidenceMarker, String answerGoldStatus) {}

    private record SelectedCase(String queryId, String query, RagBenchmarkDataset.Document document, int relevance) {}
    private record TextBlock(String text, boolean heading) {}
    private enum Complexity { SIMPLE, MEDIUM, COMPLEX }
}
