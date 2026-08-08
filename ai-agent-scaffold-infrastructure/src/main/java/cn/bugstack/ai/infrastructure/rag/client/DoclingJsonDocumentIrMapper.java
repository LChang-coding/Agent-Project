package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将Docling JSON转换为不丢布局来源的Canonical Document IR。
 */
final class DoclingJsonDocumentIrMapper {

    /** 将 Docling JSON 和最终 Document IR 相互转换。 */
    private final ObjectMapper objectMapper;

    /** 注入项目统一配置的 JSON 转换器。 */
    DoclingJsonDocumentIrMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 转换Docling结构化响应；未知字段被保留在原始parser-output产物中。 */
    MappedDocument map(RagDocumentParserPort.ParseCommand command, JsonNode json, String markdown,
                       String parserRevision) {
        Set<String> warnings = new LinkedHashSet<>();
        Map<Integer, PageDraft> pages = pages(json, warnings);
        List<BlockDraft> drafts = new ArrayList<>();
        readTextBlocks(json == null ? null : json.get("texts"), drafts, warnings);
        readTables(json == null ? null : json.get("tables"), drafts, warnings);
        readPictures(json == null ? null : json.get("pictures"), drafts, warnings);
        if (drafts.isEmpty() && markdown != null && !markdown.isBlank()) {
            warnings.add("DOCLING_JSON_BLOCKS_MISSING_MARKDOWN_FALLBACK");
            drafts.add(new BlockDraft(1, 0, DocumentIr.BlockType.PARAGRAPH, markdown,
                    null, null, 0, 0, List.of(), "und", 0.5, Set.of()));
        }
        drafts.sort(Comparator.comparingInt(BlockDraft::pageNumber)
                .thenComparingInt(BlockDraft::readingOrder));
        List<HeadingDraft> headingStack = new ArrayList<>();
        Map<Integer, List<DocumentIr.Block>> blocksByPage = new LinkedHashMap<>();
        int fallbackOffset = 0;
        for (int index = 0; index < drafts.size(); index++) {
            BlockDraft draft = drafts.get(index);
            if (draft.type() == DocumentIr.BlockType.TITLE
                    || draft.type() == DocumentIr.BlockType.HEADING) {
                int level = Math.max(1, Math.min(6, draft.headingLevel()));
                while (!headingStack.isEmpty()
                        && headingStack.get(headingStack.size() - 1).level() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(new HeadingDraft(level, draft.text()));
            }
            List<String> headingPath = headingStack.stream().map(HeadingDraft::text).toList();
            int start = draft.startOffset() >= 0 ? draft.startOffset() : fallbackOffset;
            int end = draft.endOffset() >= start ? draft.endOffset() : start + draft.text().length();
            fallbackOffset = Math.max(fallbackOffset, end + 1);
            Set<DocumentIr.Flag> flags = new LinkedHashSet<>(draft.flags());
            if (draft.table() != null) flags.add(DocumentIr.Flag.TABLE_STRUCTURE);
            DocumentIr.Block block = new DocumentIr.Block(stableId(command.versionId(), index, draft),
                    draft.type(), draft.text(), draft.text(),
                    new DocumentIr.SourceSpan(start, end,
                            "docling"),
                    draft.boundingBox(), draft.table(), draft.readingOrder(),
                    "page-" + draft.pageNumber(), draft.columnIndex(), List.copyOf(headingPath),
                    draft.language(), draft.confidence(), flags, false, true, "", List.of());
            blocksByPage.computeIfAbsent(draft.pageNumber(), ignored -> new ArrayList<>()).add(block);
        }
        blocksByPage.keySet().forEach(page -> pages.computeIfAbsent(page,
                key -> new PageDraft(key, 0, 0)));
        if (pages.isEmpty()) pages.put(1, new PageDraft(1, 0, 0));
        List<DocumentIr.Page> irPages = pages.values().stream()
                .sorted(Comparator.comparingInt(PageDraft::pageNumber))
                .map(page -> new DocumentIr.Page(page.pageNumber(), page.width(), page.height(),
                        blocksByPage.getOrDefault(page.pageNumber(), List.of())))
                .toList();
        boolean ocrApplied = drafts.stream().anyMatch(draft -> draft.flags().contains(DocumentIr.Flag.OCR_TEXT))
                || command.ocrMode() == RagDocumentParserPort.OcrMode.FORCED;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ocrMode", command.ocrMode().name());
        metadata.put("pageCount", Integer.toString(irPages.size()));
        metadata.put("blockCount", Integer.toString(drafts.size()));
        DocumentIr ir = new DocumentIr("1.0", command.versionId(), command.fileName(),
                command.mimeType(), "und", "docling", parserRevision, irPages,
                metadata, List.copyOf(warnings), ocrApplied ? Set.of(DocumentIr.Flag.OCR_TEXT) : Set.of());
        String normalized = markdown == null || markdown.isBlank() ? renderMarkdown(ir) : markdown;
        return new MappedDocument(ir, normalized, sections(ir), objectMapper.valueToTree(json).toString(),
                List.copyOf(warnings), ocrApplied);
    }

    /** 读取页码和页面尺寸；缺失页面元数据时记录质量告警。 */
    private Map<Integer, PageDraft> pages(JsonNode json, Set<String> warnings) {
        Map<Integer, PageDraft> result = new LinkedHashMap<>();
        JsonNode nodes = json == null ? null : json.get("pages");
        if (nodes == null || !nodes.isObject()) {
            warnings.add("DOCLING_PAGE_METADATA_MISSING");
            return result;
        }
        nodes.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            int number = integer(page, "page_no", parseInt(entry.getKey(), 1));
            JsonNode size = page == null ? null : page.get("size");
            double width = number(size, "width", 0);
            double height = number(size, "height", 0);
            if (number > 0) result.put(number, new PageDraft(number, width, height));
        });
        return result;
    }

    /** 将 Docling 文本节点转换为带页码、顺序、坐标和标题级别的块草稿。 */
    private void readTextBlocks(JsonNode values, List<BlockDraft> result, Set<String> warnings) {
        if (values == null || !values.isArray()) return;
        int order = 0;
        for (JsonNode node : values) {
            String text = node.path("text").asText("").strip();
            if (text.isBlank()) continue;
            Provenance provenance = provenance(node.get("prov"), order++);
            String label = node.path("label").asText("paragraph").toLowerCase(Locale.ROOT);
            DocumentIr.BlockType type = blockType(label);
            int level = integer(node, "level", type == DocumentIr.BlockType.TITLE ? 1 : 2);
            Set<DocumentIr.Flag> flags = new LinkedHashSet<>();
            if (node.path("source").asText("").toLowerCase(Locale.ROOT).contains("ocr")
                    || node.path("ocr").asBoolean(false)) flags.add(DocumentIr.Flag.OCR_TEXT);
            if (provenance.boundingBox() == null) warnings.add("DOCLING_BLOCK_PROVENANCE_INCOMPLETE");
            result.add(new BlockDraft(provenance.pageNumber(), provenance.order(), type, text,
                    provenance.boundingBox(), null, provenance.startOffset(), provenance.endOffset(),
                    List.of(), node.path("language").asText("und"),
                    confidence(node), flags, level, 0));
        }
    }

    /** 保留表格单元格、行列跨度和 provenance，禁止只压成纯文本。 */
    private void readTables(JsonNode values, List<BlockDraft> result, Set<String> warnings) {
        if (values == null || !values.isArray()) return;
        int tableOrder = 100_000;
        for (JsonNode node : values) {
            Provenance provenance = provenance(node.get("prov"), tableOrder++);
            JsonNode cellsNode = node.path("data").path("table_cells");
            if (!cellsNode.isArray()) cellsNode = node.path("table_cells");
            List<DocumentIr.TableCell> cells = new ArrayList<>();
            int maxRow = -1;
            if (cellsNode.isArray()) {
                for (JsonNode cell : cellsNode) {
                    int rowStart = integer(cell, "start_row_offset_idx", integer(cell, "row", 0));
                    int rowEnd = integer(cell, "end_row_offset_idx", rowStart + 1);
                    int colStart = integer(cell, "start_col_offset_idx", integer(cell, "col", 0));
                    int colEnd = integer(cell, "end_col_offset_idx", colStart + 1);
                    String text = cell.path("text").asText("");
                    cells.add(new DocumentIr.TableCell(rowStart, colStart,
                            Math.max(1, rowEnd - rowStart), Math.max(1, colEnd - colStart),
                            text, text, null, null, Set.of()));
                    maxRow = Math.max(maxRow, rowStart);
                }
            }
            List<DocumentIr.TableRow> rows = new ArrayList<>();
            for (int row = 0; row <= maxRow; row++) {
                int rowIndex = row;
                rows.add(new DocumentIr.TableRow(row, cells.stream()
                        .filter(cell -> cell.rowIndex() == rowIndex)
                        .sorted(Comparator.comparingInt(DocumentIr.TableCell::columnIndex)).toList()));
            }
            if (rows.isEmpty()) warnings.add("DOCLING_TABLE_STRUCTURE_INCOMPLETE");
            DocumentIr.Table table = new DocumentIr.Table(rows);
            String text = rows.stream().map(row -> row.cells().stream()
                            .map(DocumentIr.TableCell::normalizedText)
                            .collect(java.util.stream.Collectors.joining(" | ")))
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (text.isBlank()) text = node.path("text").asText("[未能结构化的表格]");
            result.add(new BlockDraft(provenance.pageNumber(), provenance.order(),
                    DocumentIr.BlockType.TABLE, text, provenance.boundingBox(), table,
                    provenance.startOffset(), provenance.endOffset(), List.of(), "und",
                    confidence(node), Set.of(DocumentIr.Flag.TABLE_STRUCTURE), 0, 0));
        }
    }

    /** 图片只记录可追溯说明与位置，未做视觉理解时显式告警。 */
    private void readPictures(JsonNode values, List<BlockDraft> result, Set<String> warnings) {
        if (values == null || !values.isArray()) return;
        int order = 200_000;
        for (JsonNode node : values) {
            Provenance provenance = provenance(node.get("prov"), order++);
            String text = node.path("text").asText(node.path("caption").asText("")).strip();
            if (text.isBlank()) {
                warnings.add("DOCLING_IMAGE_WITHOUT_TEXT_DESCRIPTION");
                continue;
            }
            result.add(new BlockDraft(provenance.pageNumber(), provenance.order(),
                    DocumentIr.BlockType.IMAGE, text, provenance.boundingBox(), null,
                    provenance.startOffset(), provenance.endOffset(), List.of(), "und",
                    confidence(node), Set.of(), 0, 0));
        }
    }

    /** 从首条 provenance 读取页码、阅读顺序、坐标和字符范围。 */
    private Provenance provenance(JsonNode values, int fallbackOrder) {
        JsonNode value = values != null && values.isArray() && !values.isEmpty() ? values.get(0) : null;
        int page = integer(value, "page_no", 1);
        JsonNode bbox = value == null ? null : value.get("bbox");
        DocumentIr.BoundingBox boundingBox = boundingBox(page, bbox);
        JsonNode span = value == null ? null : value.get("charspan");
        int start = span != null && span.isArray() && span.size() >= 2 ? span.get(0).asInt(-1) : -1;
        int end = span != null && span.isArray() && span.size() >= 2 ? span.get(1).asInt(-1) : -1;
        return new Provenance(Math.max(1, page), integer(value, "reading_order", fallbackOrder),
                boundingBox, start, end);
    }

    /** 校验坐标边界并转换为页内矩形；非法或缺失坐标返回空值。 */
    private DocumentIr.BoundingBox boundingBox(int page, JsonNode bbox) {
        if (bbox == null || !bbox.isObject()) return null;
        double left = number(bbox, "l", number(bbox, "left", -1));
        double top = number(bbox, "t", number(bbox, "top", -1));
        double right = number(bbox, "r", number(bbox, "right", -1));
        double bottom = number(bbox, "b", number(bbox, "bottom", -1));
        if (left < 0 || top < 0 || right < left || bottom < top) return null;
        return new DocumentIr.BoundingBox(Math.max(1, page), left, top, right, bottom);
    }

    /** 将 Docling 标签映射到平台统一块类型，未知标签按正文段落处理。 */
    private DocumentIr.BlockType blockType(String label) {
        return switch (label) {
            case "title" -> DocumentIr.BlockType.TITLE;
            case "section_header", "heading" -> DocumentIr.BlockType.HEADING;
            case "list_item" -> DocumentIr.BlockType.LIST_ITEM;
            case "caption" -> DocumentIr.BlockType.CAPTION;
            case "page_header", "header" -> DocumentIr.BlockType.HEADER;
            case "page_footer", "footer" -> DocumentIr.BlockType.FOOTER;
            case "page_number" -> DocumentIr.BlockType.PAGE_NUMBER;
            case "formula" -> DocumentIr.BlockType.FORMULA;
            case "footnote" -> DocumentIr.BlockType.FOOTNOTE;
            case "code" -> DocumentIr.BlockType.CODE;
            default -> DocumentIr.BlockType.PARAGRAPH;
        };
    }

    /** 从可检索正文块生成兼容检索入库接口的段落列表。 */
    private List<RagDocumentParserPort.ParsedSection> sections(DocumentIr ir) {
        List<RagDocumentParserPort.ParsedSection> result = new ArrayList<>();
        for (DocumentIr.Block block : ir.blocks()) {
            if (!block.retrievable() || block.normalizedText().isBlank()
                    || block.type() == DocumentIr.BlockType.HEADING
                    || block.type() == DocumentIr.BlockType.TITLE) continue;
            Integer page = block.boundingBox() == null ? null : block.boundingBox().pageNumber();
            result.add(new RagDocumentParserPort.ParsedSection(String.join(" / ", block.headingPath()),
                    block.normalizedText(), page, result.size()));
        }
        return List.copyOf(result);
    }

    /** 当 Docling 未返回 Markdown 时，按标题和正文块生成可展示文本。 */
    private String renderMarkdown(DocumentIr ir) {
        StringBuilder result = new StringBuilder();
        for (DocumentIr.Block block : ir.blocks()) {
            if (block.normalizedText().isBlank()) continue;
            if (!result.isEmpty()) result.append("\n\n");
            if (block.type() == DocumentIr.BlockType.TITLE) result.append("# ");
            else if (block.type() == DocumentIr.BlockType.HEADING) result.append("## ");
            result.append(block.normalizedText());
        }
        return result.toString().strip();
    }

    /** 读取零到一之间的置信度，非法值回退为完全可信。 */
    private double confidence(JsonNode node) {
        double value = number(node, "confidence", 1.0);
        return Double.isFinite(value) && value >= 0 && value <= 1 ? value : 1.0;
    }

    /** 读取可转换为整数的字段，否则使用调用方提供的默认值。 */
    private int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    /** 读取数值字段，否则使用调用方提供的默认值。 */
    private double number(JsonNode node, String field, double fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    /** 将页面对象键转换为页码，格式不合法时使用默认页码。 */
    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** 用文档身份、顺序和内容生成稳定块 ID，重跑结果可对比。 */
    private String stableId(String documentId, int index, BlockDraft draft) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (documentId + '\0' + index + '\0' + draft.pageNumber() + '\0' + draft.type()
                            + '\0' + draft.text()).getBytes(StandardCharsets.UTF_8));
            return "blk_" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }

    /** Docling 响应转换后的结构化文档、展示文本、兼容段落和质量信息。 */
    record MappedDocument(DocumentIr ir, String normalizedMarkdown,
                          List<RagDocumentParserPort.ParsedSection> sections,
                          String parserOutputJson, List<String> warnings, boolean ocrApplied) {
    }

    /** 尚未绑定正文块的页面尺寸草稿。 */
    private record PageDraft(int pageNumber, double width, double height) {
    }

    /** 构造当前标题路径所需的标题级别和文本。 */
    private record HeadingDraft(int level, String text) {
    }

    /** Docling 节点在原文中的页、顺序、坐标和字符范围。 */
    private record Provenance(int pageNumber, int order, DocumentIr.BoundingBox boundingBox,
                              int startOffset, int endOffset) {
    }

    /** 转换为最终 Document IR 块之前的 Docling 节点数据。 */
    private record BlockDraft(int pageNumber, int readingOrder, DocumentIr.BlockType type, String text,
                              DocumentIr.BoundingBox boundingBox, DocumentIr.Table table,
                              int startOffset, int endOffset, List<String> headingPath, String language,
                              double confidence, Set<DocumentIr.Flag> flags, int headingLevel,
                              int columnIndex) {
        /** 为没有标题级别和分栏信息的块提供默认值。 */
        private BlockDraft(int pageNumber, int readingOrder, DocumentIr.BlockType type, String text,
                           DocumentIr.BoundingBox boundingBox, DocumentIr.Table table,
                           int startOffset, int endOffset, List<String> headingPath, String language,
                           double confidence, Set<DocumentIr.Flag> flags) {
            this(pageNumber, readingOrder, type, text, boundingBox, table, startOffset, endOffset,
                    headingPath, language, confidence, flags, 0, 0);
        }
    }
}
