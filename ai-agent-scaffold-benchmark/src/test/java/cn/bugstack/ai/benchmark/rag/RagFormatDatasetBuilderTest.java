package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFormatDatasetBuilderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildPairedDeterministicReadableDatasetAndKeepGoldClosed() throws Exception {
        Source source = source(230);
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        RagFormatDatasetBuilder builder = new RagFormatDatasetBuilder(new ObjectMapper());
        RagFormatDatasetBuilder.Configuration configuration = configuration(source, first);

        RagFormatDatasetBuilder.Manifest manifest = builder.build(configuration);
        RagFormatDatasetBuilder.Manifest repeated = builder.build(configuration(source, second));
        RagFormatDatasetValidator.Report validation = new RagFormatDatasetValidator(new ObjectMapper())
                .validate(first);

        assertEquals(200, manifest.pairedDocumentCount());
        assertEquals(200, manifest.queryCount());
        assertEquals(200, manifest.qrelCount());
        assertEquals(200, manifest.formatCounts().get("PDF"));
        assertEquals(200, manifest.formatCounts().get("DOCX"));
        assertEquals(80, manifest.complexityCountsPerFormat().get("SIMPLE"));
        assertEquals(70, manifest.complexityCountsPerFormat().get("MEDIUM"));
        assertEquals(50, manifest.complexityCountsPerFormat().get("COMPLEX"));
        assertEquals(manifest.treeSha256(), repeated.treeSha256());
        assertTrue(validation.valid(), validation.failures().toString());
        assertEquals(400, validation.formatDocumentCount());
        assertEquals(200, count(first.resolve("prepared/pdf"), ".pdf"));
        assertEquals(200, count(first.resolve("prepared/docx"), ".docx"));

        List<JsonNode> documents = jsonLines(first.resolve("manifests/documents.jsonl"));
        assertEquals(400, documents.size());
        assertEquals(400, documents.stream().map(node -> node.path("formatDocumentId").asText()).distinct().count());
        Set<String> sourceDocumentIds = new HashSet<>();
        documents.forEach(node -> sourceDocumentIds.add(node.path("sourceDocumentId").asText()));
        assertEquals(200, sourceDocumentIds.size());

        JsonNode pdfNode = documents.stream().filter(node -> "PDF".equals(node.path("format").asText())).findFirst()
                .orElseThrow();
        Path pdf = first.resolve(pdfNode.path("relativePath").asText());
        String pdfText;
        try (var document = Loader.loadPDF(pdf.toFile())) {
            pdfText = new PDFTextStripper().getText(document);
        }
        assertTrue(pdfText.contains(pdfNode.path("evidenceMarker").asText()));

        JsonNode docxNode = documents.stream().filter(node -> "DOCX".equals(node.path("format").asText())).findFirst()
                .orElseThrow();
        Path docx = first.resolve(docxNode.path("relativePath").asText());
        StringBuilder docxText = new StringBuilder();
        try (InputStream input = Files.newInputStream(docx); XWPFDocument document = new XWPFDocument(input)) {
            document.getParagraphs().forEach(paragraph -> docxText.append(paragraph.getText()).append('\n'));
        }
        assertTrue(docxText.toString().contains(docxNode.path("evidenceMarker").asText()));

        Set<String> qrelDocuments = new HashSet<>();
        List<String> qrels = Files.readAllLines(first.resolve("gold/qrels.tsv"), StandardCharsets.UTF_8);
        qrels.stream().skip(1).forEach(line -> qrelDocuments.add(line.split("\\t")[1]));
        assertEquals(sourceDocumentIds, qrelDocuments);
        assertEquals(200, jsonLines(first.resolve("gold/queries.jsonl")).size());
        assertEquals(200, jsonLines(first.resolve("gold/gold.jsonl")).size());
    }

    @Test
    void shouldRejectOverwriteAndInsufficientUniqueSingleGoldDocuments() throws Exception {
        Source sufficient = source(200);
        Path output = temporaryDirectory.resolve("existing");
        RagFormatDatasetBuilder builder = new RagFormatDatasetBuilder(new ObjectMapper());
        builder.build(configuration(sufficient, output));
        assertThrows(IllegalArgumentException.class, () -> builder.build(configuration(sufficient, output)));

        Source insufficient = source(199);
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(configuration(insufficient, temporaryDirectory.resolve("insufficient"))));
    }

    private RagFormatDatasetBuilder.Configuration configuration(Source source, Path output) {
        return new RagFormatDatasetBuilder.Configuration("test-format-dataset", source.corpus(), source.queries(),
                source.qrels(), output, "https://example.test/scifact.zip", "test-revision",
                "CC-BY-4.0_annotations_ODC-By-1.0_S2ORC", 20260729L);
    }

    private Source source(int count) throws Exception {
        List<String> corpus = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        List<String> qrels = new ArrayList<>();
        qrels.add("query-id\tcorpus-id\tscore");
        for (int index = 1; index <= count; index++) {
            corpus.add("{\"_id\":\"d" + index + "\",\"title\":\"Title " + index
                    + "\",\"text\":\"Evidence sentence " + index
                    + " is stable. A second sentence verifies parser continuity.\"}");
            queries.add("{\"_id\":\"q" + index + "\",\"text\":\"claim " + index + "\"}");
            qrels.add("q" + index + "\td" + index + "\t1");
        }
        return new Source(write("corpus-" + count + ".jsonl", corpus),
                write("queries-" + count + ".jsonl", queries),
                write("qrels-" + count + ".tsv", qrels));
    }

    private Path write(String name, List<String> lines) throws Exception {
        return Files.write(temporaryDirectory.resolve(name), lines, StandardCharsets.UTF_8);
    }

    private long count(Path directory, String suffix) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private List<JsonNode> jsonLines(Path path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> values = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) values.add(mapper.readTree(line));
        }
        return values;
    }

    private record Source(Path corpus, Path queries, Path qrels) {}
}
