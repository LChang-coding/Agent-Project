package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBenchmarkArtifactWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWriteAuditableMarkdownWithoutAllowingCorpusHeadingsToEscapeDocumentScope() throws Exception {
        RagBenchmarkDataset dataset = new RagBenchmarkDataset(
                Map.of("文档/1", new RagBenchmarkDataset.Document("文档/1", "Title #1",
                        "paragraph\n# untrusted heading\nmore")),
                Map.of("q1", "question"), Map.of("q1", Map.of("文档/1", 1)));
        Path corpus = write("corpus.jsonl", "source-corpus");
        Path queries = write("queries.jsonl", "source-queries");
        Path qrels = write("test.tsv", "source-qrels");
        Path output = temporaryDirectory.resolve("out");

        RagBenchmarkArtifactWriter writer = new RagBenchmarkArtifactWriter(new ObjectMapper());
        RagBenchmarkArtifactWriter.Manifest manifest = writer.write(dataset, output,
                new RagBenchmarkArtifactWriter.Configuration("fixture", "https://example.invalid/fixture",
                        "revision-1", "CC-BY-4.0", "full", 7L, 4096),
                new RagBenchmarkArtifactWriter.SourceFiles(corpus, queries, qrels));

        Path shard = output.resolve("documents/benchmark-0001.md");
        String markdown = Files.readString(shard, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("\\# untrusted heading"));
        assertFalse(markdown.contains("\n# untrusted heading"));
        String marker = RagBenchmarkArtifactWriter.marker("文档/1");
        assertEquals("文档/1", RagBenchmarkArtifactWriter.documentIdFromHeading("root / " + marker + " — title"));
        assertEquals(1, manifest.documentCount());
        assertTrue(Files.isRegularFile(output.resolve("manifest.json")));
        assertThrows(IllegalArgumentException.class, () -> writer.write(dataset, output,
                new RagBenchmarkArtifactWriter.Configuration("fixture", "https://example.invalid/fixture",
                        "revision-1", "CC-BY-4.0", "full", 7L, 4096),
                new RagBenchmarkArtifactWriter.SourceFiles(corpus, queries, qrels)));
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(temporaryDirectory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
