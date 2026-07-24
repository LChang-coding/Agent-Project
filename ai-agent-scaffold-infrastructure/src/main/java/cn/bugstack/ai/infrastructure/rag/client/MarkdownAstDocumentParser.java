package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于CommonMark AST的Markdown结构化解析器。
 * <p>AST是Markdown语义的主来源；原始Markdown仅作为可追溯展示产物保留。</p>
 */
final class MarkdownAstDocumentParser {

    static final String PARSER_NAME = "commonmark-java";
    static final String PARSER_REVISION = "commonmark-java-0.28.0-ir-v1";
    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(), TaskListItemsExtension.create(), YamlFrontMatterExtension.create());

    private final Parser parser = Parser.builder().extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private final ObjectMapper objectMapper;

    MarkdownAstDocumentParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将UTF-8 Markdown转换为Document IR。 */
    RagDocumentParserPort.ParsedDocument parse(RagDocumentParserPort.ParseCommand command, String markdown) {
        Node root = parser.parse(markdown);
        Map<String, String> metadata = frontMatter(root);
        List<String> warnings = syntaxWarnings(markdown, root);
        List<DocumentIr.Block> blocks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        int[] order = {0};
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            appendTopLevel(node, markdown, command.versionId(), headings, blocks, order);
        }
        if (blocks.stream().noneMatch(block -> block.retrievable() && !block.normalizedText().isBlank())) {
            throw new AppException("RAG_DOCUMENT_TEXT_INVALID", "Markdown AST未产生可检索正文");
        }
        DocumentIr ir = new DocumentIr("1.0", command.versionId(), command.fileName(),
                command.mimeType(), "und", PARSER_NAME, PARSER_REVISION,
                List.of(new DocumentIr.Page(1, 0, 0, blocks)), metadata, warnings, Set.of());
        String parserOutput;
        try {
            parserOutput = objectMapper.writeValueAsString(ir);
        } catch (JsonProcessingException error) {
            throw new AppException("RAG_MARKDOWN_AST_SERIALIZE_FAILED", "Markdown AST产物序列化失败", error);
        }
        List<RagDocumentParserPort.ParsedSection> sections = sections(blocks);
        return new RagDocumentParserPort.ParsedDocument(markdown, sections, 0, PARSER_REVISION,
                Map.of("parser", PARSER_NAME, "mimeType", command.mimeType()), ir,
                parserOutput, warnings, false);
    }

    private void appendTopLevel(Node node, String source, String documentId, List<String> headings,
                                List<DocumentIr.Block> blocks, int[] order) {
        if (node instanceof Heading heading) {
            String text = inlineText(heading).strip();
            while (headings.size() >= heading.getLevel()) headings.remove(headings.size() - 1);
            headings.add(text);
            DocumentIr.BlockType type = heading.getLevel() == 1
                    ? DocumentIr.BlockType.TITLE : DocumentIr.BlockType.HEADING;
            blocks.add(block(documentId, node, source, type,
                    text, headings, null, order[0]++));
            return;
        }
        if (node instanceof TableBlock table) {
            DocumentIr.Table structure = table(table);
            String text = tableText(structure);
            blocks.add(block(documentId, node, source, DocumentIr.BlockType.TABLE,
                    text, headings, structure, order[0]++));
            return;
        }
        if (node instanceof FencedCodeBlock fenced) {
            blocks.add(block(documentId, node, source, DocumentIr.BlockType.CODE,
                    fenced.getLiteral(), headings, null, order[0]++));
            return;
        }
        if (node instanceof IndentedCodeBlock indented) {
            blocks.add(block(documentId, node, source, DocumentIr.BlockType.CODE,
                    indented.getLiteral(), headings, null, order[0]++));
            return;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof ListItem) {
                    blocks.add(block(documentId, child, source, DocumentIr.BlockType.LIST_ITEM,
                            inlineText(child).strip(), headings, null, order[0]++));
                }
            }
            return;
        }
        if (node instanceof BlockQuote) {
            blocks.add(block(documentId, node, source, DocumentIr.BlockType.QUOTE,
                    inlineText(node).strip(), headings, null, order[0]++));
            return;
        }
        if (node instanceof HtmlBlock) {
            blocks.add(block(documentId, node, source, DocumentIr.BlockType.OTHER,
                    raw(node, source).strip(), headings, null, order[0]++));
            return;
        }
        if (node instanceof Paragraph) {
            DocumentIr.BlockType type = paragraphType(node, source);
            blocks.add(block(documentId, node, source, type,
                    inlineText(node).strip(), headings, null, order[0]++));
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            appendTopLevel(child, source, documentId, headings, blocks, order);
        }
    }

    private DocumentIr.Block block(String documentId, Node node, String source, DocumentIr.BlockType type,
                                   String normalized, List<String> headings, DocumentIr.Table table, int order) {
        int start = sourceStart(node);
        int end = sourceEnd(node);
        String raw = start >= 0 && end >= start && end <= source.length()
                ? source.substring(start, end) : normalized;
        return new DocumentIr.Block(stableId(documentId, order, type, raw), type, raw, normalized,
                new DocumentIr.SourceSpan(Math.max(0, start), Math.max(Math.max(0, start), end),
                        "markdown"),
                null, table, order, "markdown-page-1", 0, List.copyOf(headings),
                "und", 1.0, table == null ? Set.of() : Set.of(DocumentIr.Flag.TABLE_STRUCTURE),
                false, true, "", List.of());
    }

    private Map<String, String> frontMatter(Node root) {
        YamlFrontMatterVisitor visitor = new YamlFrontMatterVisitor();
        root.accept(visitor);
        Map<String, String> result = new LinkedHashMap<>();
        visitor.getData().forEach((key, values) -> result.put("frontMatter." + key, String.join("\n", values)));
        return Map.copyOf(result);
    }

    private List<String> syntaxWarnings(String markdown, Node root) {
        Set<String> warnings = new LinkedHashSet<>();
        if (markdown.lines().anyMatch(line -> line.matches("^\\[\\^[^]]+]:.*"))) {
            warnings.add("MARKDOWN_FOOTNOTE_PRESERVED_AS_TEXT");
        }
        if (markdown.contains("$$") || markdown.matches("(?s).*\\\\\\(.+?\\\\\\).*")) {
            warnings.add("MARKDOWN_FORMULA_PRESERVED_AS_TEXT");
        }
        root.accept(new AbstractVisitor() {
            @Override
            public void visit(HtmlBlock htmlBlock) {
                warnings.add("MARKDOWN_HTML_BLOCK_UNTRUSTED");
                visitChildren(htmlBlock);
            }

            @Override
            public void visit(Image image) {
                if (inlineText(image).isBlank()) warnings.add("MARKDOWN_IMAGE_WITHOUT_ALT");
                visitChildren(image);
            }
        });
        return List.copyOf(warnings);
    }

    private DocumentIr.BlockType paragraphType(Node node, String source) {
        String raw = raw(node, source).strip();
        if (raw.startsWith("[^") && raw.contains("]:")) return DocumentIr.BlockType.FOOTNOTE;
        if (raw.startsWith("$$") || raw.startsWith("\\[") || raw.startsWith("\\(")) {
            return DocumentIr.BlockType.FORMULA;
        }
        return DocumentIr.BlockType.PARAGRAPH;
    }

    private DocumentIr.Table table(TableBlock tableBlock) {
        List<DocumentIr.TableRow> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Node node = tableBlock.getFirstChild(); node != null; node = node.getNext()) {
            for (Node row = node.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof org.commonmark.ext.gfm.tables.TableRow)) continue;
                List<DocumentIr.TableCell> cells = new ArrayList<>();
                int column = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof org.commonmark.ext.gfm.tables.TableCell) {
                        String text = inlineText(cell).strip();
                        cells.add(new DocumentIr.TableCell(rowIndex, column++, 1, 1,
                                text, text, null, null, Set.of()));
                    }
                }
                rows.add(new DocumentIr.TableRow(rowIndex++, cells));
            }
        }
        return new DocumentIr.Table(rows);
    }

    private String tableText(DocumentIr.Table table) {
        return table.rows().stream().map(row -> row.cells().stream()
                        .map(DocumentIr.TableCell::normalizedText)
                        .collect(java.util.stream.Collectors.joining(" | ")))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<RagDocumentParserPort.ParsedSection> sections(List<DocumentIr.Block> blocks) {
        List<RagDocumentParserPort.ParsedSection> result = new ArrayList<>();
        for (DocumentIr.Block block : blocks) {
            if (!block.retrievable() || block.type() == DocumentIr.BlockType.HEADING
                    || block.type() == DocumentIr.BlockType.TITLE
                    || block.normalizedText().isBlank()) continue;
            result.add(new RagDocumentParserPort.ParsedSection(String.join(" / ", block.headingPath()),
                    block.normalizedText(), null, result.size()));
        }
        return List.copyOf(result);
    }

    private String inlineText(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Text value) { text.append(value.getLiteral()); }
            @Override public void visit(Code value) { text.append(value.getLiteral()); }
            @Override public void visit(SoftLineBreak value) { text.append('\n'); }
            @Override public void visit(HardLineBreak value) { text.append('\n'); }
            @Override public void visit(Emphasis value) { visitChildren(value); }
            @Override public void visit(StrongEmphasis value) { visitChildren(value); }
            @Override public void visit(Link value) { visitChildren(value); }
            @Override public void visit(Image value) { visitChildren(value); }
        });
        return text.toString();
    }

    private String raw(Node node, String source) {
        int start = sourceStart(node);
        int end = sourceEnd(node);
        return start >= 0 && end >= start && end <= source.length() ? source.substring(start, end) : "";
    }

    private int sourceStart(Node node) {
        return node.getSourceSpans().isEmpty() ? -1 : node.getSourceSpans().get(0).getInputIndex();
    }

    private int sourceEnd(Node node) {
        if (node.getSourceSpans().isEmpty()) return -1;
        org.commonmark.node.SourceSpan last = node.getSourceSpans().get(node.getSourceSpans().size() - 1);
        return last.getInputIndex() + last.getLength();
    }

    private String stableId(String documentId, int order, DocumentIr.BlockType type, String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (documentId + '\0' + order + '\0' + type + '\0' + raw).getBytes(StandardCharsets.UTF_8));
            return "blk_" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }
}
