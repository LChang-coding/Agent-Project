package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.document.DocumentIr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于Canonical Document IR的结构感知Parent/Child分块器。
 * <p>引用展示文本与Embedding检索文本分离；标题上下文只进入检索副本。</p>
 */
public final class DocumentIrChunker {

    public static final String CHUNKER_VERSION = "document-ir-java-v1";
    public static final String TOKENIZER_VERSION = "approx-unicode-v1-explicit-fallback";

    /** 将可检索Block转换为稳定父子分块。 */
    public ChunkingResult chunk(String sourceId, DocumentIr document, Config config) {
        if (sourceId == null || sourceId.isBlank() || document == null || config == null) {
            throw new IllegalArgumentException("IR分块来源、文档和配置不能为空");
        }
        List<Unit> units = units(document, config);
        if (units.isEmpty()) return new ChunkingResult(List.of(), List.of("NO_RETRIEVABLE_BLOCKS"));
        List<ChildDraft> children = childDrafts(units, document, config);
        List<ParentGroup> parents = parentGroups(children, config);
        return materialize(sourceId, document, parents);
    }

    private List<Unit> units(DocumentIr document, Config config) {
        List<Unit> result = new ArrayList<>();
        for (DocumentIr.Page page : document.pages()) {
            for (DocumentIr.Block block : page.blocks().stream()
                    .sorted(Comparator.comparingInt(DocumentIr.Block::readingOrder)).toList()) {
                if (!block.retrievable() || block.normalizedText().isBlank()
                        || block.type() == DocumentIr.BlockType.HEADER
                        || block.type() == DocumentIr.BlockType.FOOTER
                        || block.type() == DocumentIr.BlockType.PAGE_NUMBER
                        || block.type() == DocumentIr.BlockType.WATERMARK
                        || block.type() == DocumentIr.BlockType.TITLE
                        || block.type() == DocumentIr.BlockType.HEADING) continue;
                if (block.type() == DocumentIr.BlockType.TABLE && block.table() != null
                        && !block.table().rows().isEmpty()) {
                    result.addAll(tableUnits(block, page.pageNumber(), config));
                } else {
                    result.add(new Unit(block.normalizedText(), block.headingPath(), page.pageNumber(),
                            page.pageNumber(), List.of(block.blockId()), sourceSpans(block),
                            qualityFlags(block), block.type()));
                }
            }
        }
        return result;
    }

    private List<Unit> tableUnits(DocumentIr.Block block, int pageNumber, Config config) {
        List<DocumentIr.TableRow> rows = block.table().rows();
        if (rows.isEmpty()) return List.of();
        DocumentIr.TableRow header = rows.get(0);
        String headerText = rowText(header);
        List<Unit> result = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (int index = rows.size() > 1 ? 1 : 0; index < rows.size(); index++) {
            String row = rowText(rows.get(index));
            String candidate = tableText(headerText, group, row);
            if (!group.isEmpty() && !fits(candidate, config.childMaxChars(), config.childMaxTokens())) {
                result.add(tableUnit(block, pageNumber, tableText(headerText, group, null)));
                group.clear();
            }
            group.add(row);
        }
        if (!group.isEmpty()) {
            result.add(tableUnit(block, pageNumber, tableText(headerText, group, null)));
        } else if (rows.size() == 1) {
            result.add(tableUnit(block, pageNumber, headerText));
        }
        return result;
    }

    private Unit tableUnit(DocumentIr.Block block, int pageNumber, String text) {
        return new Unit(text, block.headingPath(), pageNumber, pageNumber, List.of(block.blockId()),
                sourceSpans(block), qualityFlags(block), DocumentIr.BlockType.TABLE);
    }

    private String tableText(String header, List<String> rows, String addition) {
        List<String> values = new ArrayList<>();
        if (header != null && !header.isBlank()) values.add(header);
        values.addAll(rows);
        if (addition != null && !addition.isBlank()) values.add(addition);
        return String.join("\n", values);
    }

    private String rowText(DocumentIr.TableRow row) {
        return row.cells().stream().sorted(Comparator.comparingInt(DocumentIr.TableCell::columnIndex))
                .map(DocumentIr.TableCell::normalizedText)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    private List<ChildDraft> childDrafts(List<Unit> units, DocumentIr document, Config config) {
        List<ChildDraft> result = new ArrayList<>();
        Unit current = null;
        for (Unit unit : units) {
            boolean atomic = atomic(unit.type());
            if (!fits(unit.displayText(), config.childMaxChars(), config.childMaxTokens())) {
                if (current != null) {
                    result.add(draft(current, document));
                    current = null;
                }
                List<String> parts = splitOversized(unit.displayText(), config.childMaxChars(),
                        config.childMaxTokens(), atomic ? 0 : config.overlapChars());
                for (String part : parts) result.add(draft(unit.withDisplayText(part), document));
                continue;
            }
            if (atomic) {
                if (current != null) {
                    result.add(draft(current, document));
                    current = null;
                }
                result.add(draft(unit, document));
                continue;
            }
            Unit combined = current == null ? unit : current.merge(unit);
            if (current != null && (!current.headingPath().equals(unit.headingPath())
                    || !fits(combined.displayText(), config.childMaxChars(), config.childMaxTokens()))) {
                result.add(draft(current, document));
                current = unit;
            } else {
                current = combined;
            }
        }
        if (current != null) result.add(draft(current, document));
        return result;
    }

    private ChildDraft draft(Unit unit, DocumentIr document) {
        String title = documentTitle(document);
        String display = unit.displayText().strip();
        String embedding = embeddingText(title, unit.headingPath(), display);
        return new ChildDraft(display, embedding, unit.headingPath(), unit.pageFrom(), unit.pageTo(),
                unit.blockIds(), unit.sourceSpans(), unit.qualityFlags(), unit.type());
    }

    private List<ParentGroup> parentGroups(List<ChildDraft> children, Config config) {
        List<ParentGroup> result = new ArrayList<>();
        List<ChildDraft> current = new ArrayList<>();
        for (ChildDraft child : children) {
            String combined = joinDisplay(current, child);
            if (!current.isEmpty() && (!current.get(0).headingPath().equals(child.headingPath())
                    || !fits(combined, config.parentMaxChars(), config.parentMaxTokens()))) {
                result.add(new ParentGroup(List.copyOf(current)));
                current.clear();
            }
            current.add(child);
        }
        if (!current.isEmpty()) result.add(new ParentGroup(List.copyOf(current)));
        return result;
    }

    private ChunkingResult materialize(String sourceId, DocumentIr document, List<ParentGroup> groups) {
        List<Partial> partials = new ArrayList<>();
        int childOrdinal = 0;
        for (int parentOrdinal = 0; parentOrdinal < groups.size(); parentOrdinal++) {
            ParentGroup group = groups.get(parentOrdinal);
            String parentDisplay = group.children().stream().map(ChildDraft::displayText)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            String parentEmbedding = embeddingText(documentTitle(document),
                    group.children().get(0).headingPath(), parentDisplay);
            String parentHash = sha256(parentDisplay);
            String parentId = stableId(sourceId, "parent", parentOrdinal, parentHash);
            partials.add(new Partial(Level.PARENT, parentId, null, parentDisplay, parentEmbedding,
                    approximateTokens(parentEmbedding), group.pageFrom(), group.pageTo(),
                    group.children().get(0).headingPath(), group.blockIds(), group.sourceSpans(),
                    parentHash, group.qualityFlags(), DocumentIr.BlockType.PARAGRAPH));
            for (ChildDraft child : group.children()) {
                String hash = sha256(child.displayText());
                partials.add(new Partial(Level.CHILD, stableId(sourceId, "child", childOrdinal++, hash),
                        parentId, child.displayText(), child.embeddingText(),
                        approximateTokens(child.embeddingText()), child.pageFrom(), child.pageTo(),
                        child.headingPath(), child.blockIds(), child.sourceSpans(), hash,
                        child.qualityFlags(), child.type()));
            }
        }
        List<Integer> childPositions = new ArrayList<>();
        for (int index = 0; index < partials.size(); index++) {
            if (partials.get(index).level() == Level.CHILD) childPositions.add(index);
        }
        Map<Integer, String> previous = new LinkedHashMap<>();
        Map<Integer, String> next = new LinkedHashMap<>();
        for (int index = 0; index < childPositions.size(); index++) {
            int position = childPositions.get(index);
            if (index > 0) previous.put(position, partials.get(childPositions.get(index - 1)).chunkId());
            if (index + 1 < childPositions.size()) {
                next.put(position, partials.get(childPositions.get(index + 1)).chunkId());
            }
        }
        List<StructuredChunk> result = new ArrayList<>();
        for (int index = 0; index < partials.size(); index++) {
            Partial value = partials.get(index);
            result.add(new StructuredChunk(value.level(), value.chunkId(), value.parentChunkId(),
                    previous.get(index), next.get(index), index, value.displayText(), value.embeddingText(),
                    value.tokenCount(), value.pageFrom(), value.pageTo(), value.headingPath(),
                    value.blockIds(), value.sourceSpans(), value.contentHash(), value.qualityFlags(),
                    Map.of("chunker_version", CHUNKER_VERSION,
                            "tokenizer_version", TOKENIZER_VERSION,
                            "chunk_level", value.level().name().toLowerCase(),
                            "block_type", value.type().name().toLowerCase())));
        }
        return new ChunkingResult(result, List.of("TOKENIZER_APPROXIMATION_ACTIVE"));
    }

    private String documentTitle(DocumentIr document) {
        return document.blocks().stream()
                .filter(block -> block.type() == DocumentIr.BlockType.TITLE)
                .map(DocumentIr.Block::normalizedText).filter(value -> !value.isBlank()).findFirst()
                .orElse(document.sourceName());
    }

    private String embeddingText(String title, List<String> headings, String displayText) {
        StringBuilder result = new StringBuilder();
        if (title != null && !title.isBlank()) result.append("文档：").append(title.strip()).append('\n');
        if (headings != null && !headings.isEmpty()) {
            result.append("章节：").append(String.join(" > ", headings)).append('\n');
        }
        result.append("正文：").append(displayText);
        return Normalizer.normalize(result, Normalizer.Form.NFKC);
    }

    private List<DocumentIr.SourceSpan> sourceSpans(DocumentIr.Block block) {
        return block.sourceSpan() == null ? List.of() : List.of(block.sourceSpan());
    }

    private Set<String> qualityFlags(DocumentIr.Block block) {
        return block.flags().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean atomic(DocumentIr.BlockType type) {
        return type == DocumentIr.BlockType.TABLE || type == DocumentIr.BlockType.CODE
                || type == DocumentIr.BlockType.FORMULA || type == DocumentIr.BlockType.IMAGE;
    }

    private String joinDisplay(List<ChildDraft> values, ChildDraft addition) {
        if (values.isEmpty()) return addition.displayText();
        return values.stream().map(ChildDraft::displayText)
                .collect(java.util.stream.Collectors.joining("\n\n")) + "\n\n" + addition.displayText();
    }

    private List<String> splitOversized(String value, int maxChars, int maxTokens, int overlapChars) {
        List<String> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            int start = result.isEmpty() ? cursor : Math.max(0, cursor - overlapChars);
            int end = Math.min(value.length(), start + maxChars);
            while (end > start && approximateTokens(value.substring(start, end)) > maxTokens) end--;
            if (end <= cursor) {
                start = cursor;
                end = Math.min(value.length(), start + Math.max(1, maxChars / 2));
            }
            int boundary = safeBoundary(value, start, end, cursor);
            String part = value.substring(start, boundary).strip();
            if (!part.isBlank()) result.add(part);
            cursor = boundary;
        }
        return result;
    }

    private int safeBoundary(String value, int start, int proposed, int consumed) {
        int minimum = Math.max(consumed + 1, start + Math.max(1, (proposed - start) / 2));
        for (int index = proposed; index >= minimum; index--) {
            char character = value.charAt(index - 1);
            if (character == '\n' || character == '。' || character == '！' || character == '？'
                    || character == '.' || character == ';' || character == ' ') return index;
        }
        return proposed;
    }

    private boolean fits(String value, int chars, int tokens) {
        return value.length() <= chars && approximateTokens(value) <= tokens;
    }

    /** 明确的兼容Tokenizer回退；产物会记录版本，不能被误认为真实模型Tokenizer。 */
    public static int approximateTokens(String value) {
        return StructuredRagChunker.approximateTokens(value);
    }

    private String stableId(String sourceId, String level, int ordinal, String contentHash) {
        return "chk_" + sha256(sourceId + '\0' + level + '\0' + ordinal + '\0' + contentHash)
                .substring(0, 48);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }

    public enum Level { PARENT, CHILD }

    /** IR分块预算。 */
    public record Config(int childMaxChars, int childMaxTokens, int parentMaxChars,
                         int parentMaxTokens, int overlapChars) {
        public Config {
            if (childMaxChars < 16 || childMaxTokens < 4 || parentMaxChars < childMaxChars
                    || parentMaxTokens < childMaxTokens || overlapChars < 0
                    || overlapChars >= childMaxChars / 2) {
                throw new IllegalArgumentException("IR分块预算非法");
            }
        }
    }

    /** 可持久化和复算的结构化分块。 */
    public record StructuredChunk(Level level, String chunkId, String parentChunkId,
                                  String previousChunkId, String nextChunkId, int chunkIndex,
                                  String displayText, String embeddingText, int tokenCount,
                                  int pageFrom, int pageTo, List<String> headingPath,
                                  List<String> blockIds, List<DocumentIr.SourceSpan> sourceSpans,
                                  String contentHash, Set<String> qualityFlags,
                                  Map<String, String> metadata) {
        public StructuredChunk {
            headingPath = List.copyOf(headingPath);
            blockIds = List.copyOf(blockIds);
            sourceSpans = List.copyOf(sourceSpans);
            qualityFlags = Set.copyOf(qualityFlags);
            metadata = Map.copyOf(metadata);
        }
    }

    /** 分块结果及显式能力告警。 */
    public record ChunkingResult(List<StructuredChunk> chunks, List<String> warnings) {
        public ChunkingResult {
            chunks = List.copyOf(chunks);
            warnings = List.copyOf(warnings);
        }
        public List<StructuredChunk> children() {
            return chunks.stream().filter(chunk -> chunk.level() == Level.CHILD).toList();
        }
    }

    private record Unit(String displayText, List<String> headingPath, int pageFrom, int pageTo,
                        List<String> blockIds, List<DocumentIr.SourceSpan> sourceSpans,
                        Set<String> qualityFlags, DocumentIr.BlockType type) {
        private Unit {
            headingPath = List.copyOf(headingPath);
            blockIds = List.copyOf(blockIds);
            sourceSpans = List.copyOf(sourceSpans);
            qualityFlags = Set.copyOf(qualityFlags);
        }
        private Unit withDisplayText(String value) {
            return new Unit(value, headingPath, pageFrom, pageTo, blockIds, sourceSpans, qualityFlags, type);
        }
        private Unit merge(Unit other) {
            List<String> ids = new ArrayList<>(blockIds);
            ids.addAll(other.blockIds);
            List<DocumentIr.SourceSpan> spans = new ArrayList<>(sourceSpans);
            spans.addAll(other.sourceSpans);
            Set<String> flags = new LinkedHashSet<>(qualityFlags);
            flags.addAll(other.qualityFlags);
            return new Unit(displayText + "\n\n" + other.displayText, headingPath,
                    Math.min(pageFrom, other.pageFrom), Math.max(pageTo, other.pageTo),
                    ids, spans, flags, type);
        }
    }

    private record ChildDraft(String displayText, String embeddingText, List<String> headingPath,
                              int pageFrom, int pageTo, List<String> blockIds,
                              List<DocumentIr.SourceSpan> sourceSpans, Set<String> qualityFlags,
                              DocumentIr.BlockType type) {
    }

    private record ParentGroup(List<ChildDraft> children) {
        private int pageFrom() { return children.stream().mapToInt(ChildDraft::pageFrom).min().orElse(1); }
        private int pageTo() { return children.stream().mapToInt(ChildDraft::pageTo).max().orElse(1); }
        private List<String> blockIds() { return children.stream().flatMap(value -> value.blockIds().stream()).distinct().toList(); }
        private List<DocumentIr.SourceSpan> sourceSpans() { return children.stream().flatMap(value -> value.sourceSpans().stream()).toList(); }
        private Set<String> qualityFlags() {
            return children.stream().flatMap(value -> value.qualityFlags().stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private record Partial(Level level, String chunkId, String parentChunkId, String displayText,
                           String embeddingText, int tokenCount, int pageFrom, int pageTo,
                           List<String> headingPath, List<String> blockIds,
                           List<DocumentIr.SourceSpan> sourceSpans, String contentHash,
                           Set<String> qualityFlags, DocumentIr.BlockType type) {
    }
}
