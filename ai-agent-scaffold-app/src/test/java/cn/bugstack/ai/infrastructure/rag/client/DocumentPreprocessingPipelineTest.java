package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.service.DocumentIrChunker;
import cn.bugstack.ai.domain.rag.service.DocumentIrCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.Assert;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 三类格式进入Canonical Document IR后的结构保真与分块契约测试。
 */
public class DocumentPreprocessingPipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void markdownShouldPreserveAstSourceTableAndSeparateEmbeddingText() throws Exception {
        String markdown = """
                ---
                owner: platform
                ---
                # 员工手册
                ## 报销规则
                | 类型 | 上限 |
                | --- | --- |
                | 酒店 | 500 |

                - [x] 提交发票

                ```java
                System.out.println("ok");
                ```
                """;
        Path root = Files.createTempDirectory("rag-markdown-ir-");
        Path source = root.resolve("handbook.md");
        Files.writeString(source, markdown, StandardCharsets.UTF_8);
        try {
            RagDocumentParserPort.ParseCommand command = command(root, source, "handbook.md",
                    "text/markdown");
            RagDocumentParserPort.ParsedDocument parsed =
                    new MarkdownAstDocumentParser(objectMapper).parse(command, markdown);

            Assert.assertEquals("platform", parsed.documentIr().metadata().get("frontMatter.owner"));
            Assert.assertTrue(parsed.documentIr().blocks().stream()
                    .anyMatch(block -> block.type() == DocumentIr.BlockType.TITLE
                            && block.normalizedText().equals("员工手册")));
            Assert.assertTrue(parsed.documentIr().blocks().stream()
                    .anyMatch(block -> block.type() == DocumentIr.BlockType.TABLE
                            && block.table() != null && block.table().cells().size() == 4));
            Assert.assertTrue(parsed.documentIr().blocks().stream()
                    .allMatch(block -> block.sourceSpan() != null
                            && block.sourceSpan().sourceLocation().equals("markdown")));
            DocumentIr cleaned = DocumentIrCleaner.standard().clean(parsed.documentIr());
            DocumentIrChunker.ChunkingResult chunking = new DocumentIrChunker().chunk(
                    "version-test", cleaned, new DocumentIrChunker.Config(300, 120, 900, 360, 20));
            Assert.assertFalse(chunking.children().isEmpty());
            Assert.assertTrue(chunking.children().toString(), chunking.children().stream()
                    .allMatch(chunk -> !chunk.embeddingText().equals(chunk.displayText())
                            && chunk.embeddingText().contains("正文:")));
            Assert.assertTrue(chunking.children().stream()
                    .anyMatch(chunk -> chunk.displayText().contains("类型 | 上限")
                            && chunk.displayText().contains("酒店 | 500")));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void docxShouldPreserveHeadingListTableAndExposeNoFakePagination() throws Exception {
        Path root = Files.createTempDirectory("rag-docx-ir-");
        Path source = root.resolve("handbook.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("员工手册");
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("差旅报销需要提交发票。");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("类型");
            table.getRow(0).getCell(1).setText("上限");
            table.getRow(1).getCell(0).setText("酒店");
            table.getRow(1).getCell(1).setText("500");
            try (OutputStream output = Files.newOutputStream(source)) {
                document.write(output);
            }
        }
        try {
            RagDocumentParserPort.ParsedDocument parsed = new DocxDocumentParser(objectMapper)
                    .parse(command(root, source, "handbook.docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

            Assert.assertEquals(0, parsed.pageCount());
            Assert.assertTrue(parsed.warnings()
                    .contains("DOCX_PAGE_NUMBER_UNAVAILABLE_WITHOUT_CONTROLLED_RENDERER"));
            Assert.assertTrue(parsed.documentIr().blocks().stream()
                    .anyMatch(block -> block.type() == DocumentIr.BlockType.TITLE
                            && block.normalizedText().equals("员工手册")));
            Assert.assertTrue(parsed.documentIr().blocks().stream()
                    .anyMatch(block -> block.type() == DocumentIr.BlockType.TABLE
                            && block.table() != null && block.table().cells().size() == 4));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void doclingJsonShouldPreservePagesBoundingBoxesReadingOrderAndTableCells() throws Exception {
        String json = """
                {
                  "pages": {
                    "1": {"page_no": 1, "size": {"width": 612, "height": 792}},
                    "2": {"page_no": 2, "size": {"width": 612, "height": 792}}
                  },
                  "texts": [
                    {"label": "section_header", "level": 2, "text": "报销规则",
                     "prov": [{"page_no": 1, "reading_order": 0,
                               "bbox": {"l": 10, "t": 20, "r": 200, "b": 40},
                               "charspan": [0, 4]}]},
                    {"label": "paragraph", "text": "需要提交发票",
                     "prov": [{"page_no": 2, "reading_order": 1,
                               "bbox": {"l": 10, "t": 50, "r": 220, "b": 80},
                               "charspan": [5, 11]}]}
                  ],
                  "tables": [
                    {"prov": [{"page_no": 2, "reading_order": 2,
                               "bbox": {"l": 10, "t": 100, "r": 300, "b": 200}}],
                     "data": {"table_cells": [
                       {"start_row_offset_idx": 0, "end_row_offset_idx": 1,
                        "start_col_offset_idx": 0, "end_col_offset_idx": 1, "text": "类型"},
                       {"start_row_offset_idx": 0, "end_row_offset_idx": 1,
                        "start_col_offset_idx": 1, "end_col_offset_idx": 2, "text": "上限"}
                     ]}}
                  ]
                }
                """;
        Path root = Files.createTempDirectory("rag-pdf-ir-");
        Path source = root.resolve("fixture.pdf");
        Files.write(source, new byte[]{1});
        try {
            DoclingJsonDocumentIrMapper.MappedDocument mapped = new DoclingJsonDocumentIrMapper(objectMapper)
                    .map(command(root, source, "fixture.pdf", "application/pdf"),
                            objectMapper.readTree(json), "", "docling-test");

            Assert.assertEquals(2, mapped.ir().pages().size());
            Assert.assertEquals(612.0, mapped.ir().pages().get(0).width(), 0);
            Assert.assertTrue(mapped.ir().blocks().stream()
                    .allMatch(block -> block.boundingBox() != null));
            Assert.assertEquals(List.of("报销规则"), mapped.ir().blocks().stream()
                    .filter(block -> block.normalizedText().equals("需要提交发票"))
                    .findFirst().orElseThrow().headingPath());
            Assert.assertTrue(mapped.ir().blocks().stream()
                    .anyMatch(block -> block.type() == DocumentIr.BlockType.TABLE
                            && block.table().cells().size() == 2));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(root);
        }
    }

    private RagDocumentParserPort.ParseCommand command(Path root, Path source,
                                                       String name, String mimeType) throws Exception {
        return new RagDocumentParserPort.ParseCommand("tenant", "job", "version", name,
                mimeType, root, source, Files.size(source), RagDocumentParserPort.OcrMode.AUTO);
    }
}
