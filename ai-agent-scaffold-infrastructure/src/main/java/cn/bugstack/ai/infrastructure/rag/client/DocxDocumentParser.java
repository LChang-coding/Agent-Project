package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Word OOXML专用结构化解析器。
 * <p>读取段落样式、编号、表格合并、页眉页脚、脚注和外部关系；实际分页不可信，因此不伪造页码。</p>
 */
final class DocxDocumentParser {

    /** 写入解析结果的解析器名称。 */
    static final String PARSER_NAME = "apache-poi-ooxml";
    /** 写入文档版本的固定解析器修订号。 */
    static final String PARSER_REVISION = "apache-poi-5.5.1-ir-v1";

    /** 序列化最终 Document IR。 */
    private final ObjectMapper objectMapper;

    /** 注入项目统一配置的 JSON 转换器。 */
    DocxDocumentParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析受控DOCX并保留OOXML语义。 */
    RagDocumentParserPort.ParsedDocument parse(RagDocumentParserPort.ParseCommand command) {
        List<DocumentIr.Block> blocks = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        List<String> headingPath = new ArrayList<>();
        int[] order = {0};
        int[] sourceOffset = {0};
        try (InputStream input = Files.newInputStream(command.contentPath());
             XWPFDocument document = new XWPFDocument(input)) {
            inspectPackage(document, warnings, metadata);
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    appendParagraph((XWPFParagraph) element, "body", headingPath, blocks,
                            order, sourceOffset, warnings);
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    appendTable((XWPFTable) element, "body", headingPath, blocks,
                            order, sourceOffset, warnings);
                }
            }
            for (XWPFHeader header : document.getHeaderList()) {
                appendAncillary(header.getParagraphs(), DocumentIr.BlockType.HEADER,
                        "header", blocks, order, sourceOffset);
            }
            for (XWPFFooter footer : document.getFooterList()) {
                appendAncillary(footer.getParagraphs(), DocumentIr.BlockType.FOOTER,
                        "footer", blocks, order, sourceOffset);
            }
            document.getFootnotes().forEach(footnote -> appendAncillary(footnote.getParagraphs(),
                    DocumentIr.BlockType.FOOTNOTE, "footnote", blocks, order, sourceOffset));
            String xml = document.getDocument().xmlText();
            if (xml.contains("<w:ins") || xml.contains("<w:del")) {
                warnings.add("DOCX_TRACKED_CHANGES_FINAL_TEXT_WITH_AUDIT_WARNING");
                metadata.put("revisionMode", "FINAL");
            }
            if (xml.contains("<w:commentRangeStart")) warnings.add("DOCX_COMMENTS_PRESENT");
            if (xml.contains("<w:txbxContent")) warnings.add("DOCX_TEXTBOX_PRESENT");
            if (xml.contains("<m:oMath")) warnings.add("DOCX_FORMULA_PRESENT");
            if (blocks.stream().noneMatch(block -> block.retrievable() && !block.normalizedText().isBlank())) {
                throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "DOCX未产生可检索正文");
            }
            warnings.add("DOCX_PAGE_NUMBER_UNAVAILABLE_WITHOUT_CONTROLLED_RENDERER");
            DocumentIr ir = new DocumentIr("1.0", command.versionId(), command.fileName(),
                    command.mimeType(), "und", PARSER_NAME, PARSER_REVISION,
                    List.of(new DocumentIr.Page(1, 0, 0, blocks)), metadata,
                    List.copyOf(warnings), Set.of());
            String markdown = displayMarkdown(blocks);
            return new RagDocumentParserPort.ParsedDocument(markdown, sections(blocks), 0,
                    PARSER_REVISION, Map.of("parser", PARSER_NAME, "mimeType", command.mimeType()),
                    ir, serialize(ir), List.copyOf(warnings), false);
        } catch (AppException error) {
            throw error;
        } catch (Exception error) {
            throw new AppException("RAG_DOCX_PARSE_FAILED", "Word OOXML结构化解析失败", error);
        }
    }

    /** 按段落样式和编号识别标题、列表或正文，并维护后续块的标题路径。 */
    private void appendParagraph(XWPFParagraph paragraph, String location, List<String> headingPath,
                                 List<DocumentIr.Block> blocks, int[] order, int[] offset,
                                 Set<String> warnings) {
        String text = paragraphText(paragraph).strip();
        if (text.isBlank()) return;
        int headingLevel = headingLevel(paragraph);
        DocumentIr.BlockType type;
        if (headingLevel > 0) {
            while (headingPath.size() >= headingLevel) headingPath.remove(headingPath.size() - 1);
            headingPath.add(text);
            type = headingLevel == 1 ? DocumentIr.BlockType.TITLE : DocumentIr.BlockType.HEADING;
        } else if (paragraph.getNumID() != null) {
            type = DocumentIr.BlockType.LIST_ITEM;
        } else {
            type = DocumentIr.BlockType.PARAGRAPH;
        }
        Set<DocumentIr.Flag> flags = new LinkedHashSet<>();
        if (paragraph.getRuns().stream().anyMatch(run -> !run.getEmbeddedPictures().isEmpty())) {
            warnings.add("DOCX_INLINE_IMAGE_PRESENT");
        }
        blocks.add(block(type, text, location + ":paragraph:" + order[0],
                headingPath, null, order[0]++, offset, flags));
    }

    /** 保留表格行列、合并关系和标题路径，不以制表符替代结构。 */
    private void appendTable(XWPFTable table, String location, List<String> headingPath,
                             List<DocumentIr.Block> blocks, int[] order, int[] offset,
                             Set<String> warnings) {
        List<DocumentIr.TableRow> rows = new ArrayList<>();
        int rowIndex = 0;
        boolean verticalMerge = false;
        for (XWPFTableRow row : table.getRows()) {
            List<DocumentIr.TableCell> cells = new ArrayList<>();
            int column = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                int columnSpan = 1;
                if (cell.getCTTc().isSetTcPr() && cell.getCTTc().getTcPr().isSetGridSpan()) {
                    BigInteger value = cell.getCTTc().getTcPr().getGridSpan().getVal();
                    if (value != null && value.signum() > 0) columnSpan = value.intValue();
                }
                if (cell.getCTTc().isSetTcPr() && cell.getCTTc().getTcPr().isSetVMerge()) {
                    verticalMerge = true;
                }
                String text = cell.getText().strip();
                cells.add(new DocumentIr.TableCell(rowIndex, column, 1, columnSpan,
                        text, text, null, null, Set.of()));
                column += columnSpan;
            }
            rows.add(new DocumentIr.TableRow(rowIndex++, cells));
        }
        if (verticalMerge) warnings.add("DOCX_VERTICAL_TABLE_MERGE_PRESERVED_WITH_APPROXIMATE_ROWSPAN");
        DocumentIr.Table structure = new DocumentIr.Table(rows);
        String text = rows.stream().map(row -> row.cells().stream()
                        .map(DocumentIr.TableCell::normalizedText)
                        .collect(java.util.stream.Collectors.joining(" | ")))
                .collect(java.util.stream.Collectors.joining("\n"));
        blocks.add(block(DocumentIr.BlockType.TABLE, text, location + ":table:" + order[0],
                headingPath, structure, order[0]++, offset, Set.of(DocumentIr.Flag.TABLE_STRUCTURE)));
    }

    /** 将页眉、页脚和脚注段落作为独立类型追加，避免混入正文。 */
    private void appendAncillary(List<XWPFParagraph> paragraphs, DocumentIr.BlockType type,
                                 String location, List<DocumentIr.Block> blocks,
                                 int[] order, int[] offset) {
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraphText(paragraph).strip();
            if (text.isBlank()) continue;
            blocks.add(block(type, text, location + ":" + order[0], List.of(),
                    null, order[0]++, offset, Set.of()));
        }
    }

    /** 使用累计字符范围和 OOXML 位置构造可追溯文档块。 */
    private DocumentIr.Block block(DocumentIr.BlockType type, String text, String location,
                                   List<String> headings, DocumentIr.Table table, int order,
                                   int[] offset, Set<DocumentIr.Flag> flags) {
        int start = offset[0];
        offset[0] += text.length() + 1;
        return new DocumentIr.Block(stableId(location, order, text), type, text, text,
                new DocumentIr.SourceSpan(start, start + text.length(), "docx"),
                null, table, order, location, 0, List.copyOf(headings), "und", 1.0,
                flags, false, true, "", List.of());
    }

    /** 从标题样式或 OOXML 大纲级别识别一到六级标题。 */
    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null) {
            String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "");
            if (normalized.matches("(heading|标题)[1-9]")) {
                char value = normalized.charAt(normalized.length() - 1);
                return Math.min(6, value - '0');
            }
        }
        if (paragraph.getCTP().isSetPPr() && paragraph.getCTP().getPPr().isSetOutlineLvl()) {
            BigInteger level = paragraph.getCTP().getPPr().getOutlineLvl().getVal();
            if (level != null && level.signum() >= 0) return Math.min(6, level.intValue() + 1);
        }
        return 0;
    }

    /** 合并段落中的文本片段，并把有说明文字的内嵌图片保留为文本提示。 */
    private String paragraphText(XWPFParagraph paragraph) {
        StringBuilder result = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String value = run.text();
            if (value != null) result.append(value);
            if (!run.getEmbeddedPictures().isEmpty()) {
                run.getEmbeddedPictures().forEach(picture -> {
                    String description = picture.getDescription();
                    if (description != null && !description.isBlank()) {
                        result.append(" [图片: ").append(description).append(']');
                    }
                });
            }
        }
        return result.isEmpty() ? paragraph.getText() : result.toString();
    }

    /** 检查外部关系、宏式内容和 OOXML 特性，记录无法保真的风险。 */
    private void inspectPackage(XWPFDocument document, Set<String> warnings,
                                Map<String, String> metadata) throws Exception {
        OPCPackage pkg = document.getPackage();
        int external = 0;
        for (PackageRelationship relationship : pkg.getRelationships()) {
            if (relationship.getTargetMode() == TargetMode.EXTERNAL) external++;
        }
        for (PackagePart part : pkg.getParts()) {
            if (part.getPartName().getName().endsWith(".rels")) continue;
            for (PackageRelationship relationship : part.getRelationships()) {
                if (relationship.getTargetMode() == TargetMode.EXTERNAL) external++;
            }
        }
        if (external > 0) {
            warnings.add("DOCX_EXTERNAL_RELATIONSHIPS_BLOCKED");
            metadata.put("externalRelationshipCount", Integer.toString(external));
        }
        if (!document.getAllEmbeddedParts().isEmpty()) {
            warnings.add("DOCX_EMBEDDED_OBJECTS_PRESENT");
            metadata.put("embeddedObjectCount", Integer.toString(document.getAllEmbeddedParts().size()));
        }
    }

    /** 将可检索正文块转换为兼容检索入库接口的段落列表。 */
    private List<RagDocumentParserPort.ParsedSection> sections(List<DocumentIr.Block> blocks) {
        List<RagDocumentParserPort.ParsedSection> result = new ArrayList<>();
        for (DocumentIr.Block block : blocks) {
            if (!block.retrievable() || block.type() == DocumentIr.BlockType.HEADING
                    || block.type() == DocumentIr.BlockType.TITLE || block.normalizedText().isBlank()) continue;
            result.add(new RagDocumentParserPort.ParsedSection(String.join(" / ", block.headingPath()),
                    block.normalizedText(), null, result.size()));
        }
        return List.copyOf(result);
    }

    /** 按标题、列表和代码块类型生成供用户查看的 Markdown。 */
    private String displayMarkdown(List<DocumentIr.Block> blocks) {
        StringBuilder result = new StringBuilder();
        for (DocumentIr.Block block : blocks) {
            if (block.normalizedText().isBlank()) continue;
            if (!result.isEmpty()) result.append("\n\n");
            if (block.type() == DocumentIr.BlockType.TITLE) result.append("# ");
            else if (block.type() == DocumentIr.BlockType.HEADING) result.append("## ");
            else if (block.type() == DocumentIr.BlockType.LIST_ITEM) result.append("- ");
            else if (block.type() == DocumentIr.BlockType.CODE) result.append("```\n");
            result.append(block.normalizedText());
            if (block.type() == DocumentIr.BlockType.CODE) result.append("\n```");
        }
        return result.toString().strip();
    }

    /** 序列化完整 Document IR，失败时转换为稳定领域错误。 */
    private String serialize(DocumentIr ir) {
        try {
            return objectMapper.writeValueAsString(ir);
        } catch (JsonProcessingException error) {
            throw new AppException("RAG_DOCX_IR_SERIALIZE_FAILED", "Word Document IR序列化失败", error);
        }
    }

    /** 基于 OOXML 位置、阅读顺序和正文生成可重放块 ID。 */
    private String stableId(String location, int order, String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (location + '\0' + order + '\0' + text).getBytes(StandardCharsets.UTF_8));
            return "blk_" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }
}
