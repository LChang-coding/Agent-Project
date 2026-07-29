package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.valobj.RagPreprocessingStrategy;
import cn.bugstack.ai.domain.rag.service.DocumentIrCleaner;
import cn.bugstack.ai.domain.rag.service.DocumentPreprocessingStrategyExecutor;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DocumentPreprocessingStrategyExecutorTest {

    private final DocumentPreprocessingStrategyExecutor executor =
            new DocumentPreprocessingStrategyExecutor(DocumentIrCleaner.standard());

    @Test
    public void shouldKeepFullIrAndRunCleaner() {
        var result = executor.execute(parsed(), RagPreprocessingStrategy.IR_FULL);

        assertTrue(result.cleaningAudits().size() >= 4);
        assertTrue(result.document().blocks().size() > 1);
        assertEquals("IR_FULL", result.document().metadata().get("preprocessing_strategy"));
        assertTrue(result.document().blocks().stream().anyMatch(block -> !block.headingPath().isEmpty()));
    }

    @Test
    public void shouldKeepIrButSkipCleaner() {
        var result = executor.execute(parsed(), RagPreprocessingStrategy.IR_NO_CLEANER);

        assertTrue(result.cleaningAudits().isEmpty());
        assertEquals(parsed().documentIr().blocks().size(), result.document().blocks().size());
        assertEquals("IR_NO_CLEANER", result.document().metadata().get("preprocessing_strategy"));
    }

    @Test
    public void shouldFlattenAfterCleanerAndLoseStructure() {
        var result = executor.execute(parsed(), RagPreprocessingStrategy.IR_NO_STRUCTURED_CHUNKING);

        assertFalse(result.cleaningAudits().isEmpty());
        assertEquals(1, result.document().blocks().size());
        assertTrue(result.document().blocks().get(0).headingPath().isEmpty());
        assertFalse(result.document().blocks().get(0).normalizedText().contains("重复页眉"));
    }

    @Test
    public void shouldSeparateLegacyMarkdownAndRawTextBaselines() {
        var legacy = executor.execute(parsed(), RagPreprocessingStrategy.LEGACY_MARKDOWN_FLATTEN);
        var raw = executor.execute(parsed(), RagPreprocessingStrategy.RAW_TEXT_CHUNK);

        assertEquals(1, legacy.document().blocks().size());
        assertEquals(1, raw.document().blocks().size());
        assertTrue(legacy.document().blocks().get(0).normalizedText().contains("## Evidence"));
        assertFalse(raw.document().blocks().get(0).normalizedText().contains("## Evidence"));
        assertNotEquals(legacy.document().blocks().get(0).normalizedText(),
                raw.document().blocks().get(0).normalizedText());
    }

    private RagDocumentParserPort.ParsedDocument parsed() {
        DocumentIr.Block header = block("header", DocumentIr.BlockType.HEADER, "重复页眉", List.of());
        DocumentIr.Block heading = block("heading", DocumentIr.BlockType.HEADING,
                "Evidence", List.of("Evidence"));
        DocumentIr.Block paragraph = block("paragraph", DocumentIr.BlockType.PARAGRAPH,
                "  Stable   scientific evidence.  ", List.of("Evidence"));
        DocumentIr ir = new DocumentIr("1.0", "doc-1", "sample.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "en", "poi", "test", List.of(new DocumentIr.Page(1, 100, 100,
                List.of(header, heading, paragraph))), Map.of(), List.of(), Set.of());
        return new RagDocumentParserPort.ParsedDocument(
                "# Sample\n\n## Evidence\n\nStable scientific evidence.",
                List.of(), 1, "test", Map.of(), ir, "{}", List.of(), false);
    }

    private DocumentIr.Block block(String id, DocumentIr.BlockType type, String text, List<String> headings) {
        return new DocumentIr.Block(id, type, text, text, new DocumentIr.SourceSpan(0, text.length()),
                null, null, 0, "", 0, headings, "en", 1.0, Set.of(),
                false, true, "", List.of());
    }
}
