package cn.bugstack.ai.benchmark.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFormatFailureInputBuilderTest {

    @TempDir
    Path temp;

    @Test
    void shouldBuildReporterInputsWithBothSourcePathsAndRefuseOverwrite() throws Exception {
        Path gold = temp.resolve("gold.jsonl");
        Path documents = temp.resolve("documents.jsonl");
        Files.writeString(gold, """
                {"queryId":"q1","query":"question","documentId":"d1","title":"Title","evidenceText":"Evidence","evidenceMarker":"MARK-d1"}
                """);
        Files.writeString(documents, """
                {"sourceDocumentId":"d1","format":"PDF","relativePath":"prepared/pdf/d1.pdf"}
                {"sourceDocumentId":"d1","format":"DOCX","relativePath":"prepared/docx/d1.docx"}
                """);
        Path out = temp.resolve("out");
        RagFormatFailureInputBuilder.Result result = new RagFormatFailureInputBuilder(new ObjectMapper()).build(
                new RagFormatFailureInputBuilder.Configuration(gold, documents, out));

        assertEquals(1, result.value().documentCount());
        assertTrue(Files.readString(result.queries()).contains("\"queryId\":\"q1\""));
        assertTrue(Files.readString(result.documents()).contains("# MARK-d1 — Title"));
        String map = Files.readString(result.documentMap());
        assertTrue(map.contains("prepared/pdf/d1.pdf"));
        assertTrue(map.contains("prepared/docx/d1.docx"));
        assertThrows(IllegalArgumentException.class, () ->
                new RagFormatFailureInputBuilder(new ObjectMapper()).build(
                        new RagFormatFailureInputBuilder.Configuration(gold, documents, out)));
    }
}
