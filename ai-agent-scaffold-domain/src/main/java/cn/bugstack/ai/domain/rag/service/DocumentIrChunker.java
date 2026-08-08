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

    /** 产物记录算法和 Token 估算版本，便于发现新旧索引混用。 */
    public static final String CHUNKER_VERSION = "document-ir-java-v1";
    /** 分块清单中记录的近似 Token 计数算法版本。 */
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

    /** 按阅读顺序提取可检索正文；版面噪声和标题只作为上下文。 */
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

    /** 表头随每组数据行重复，避免子块脱离列语义。 */
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

    /** 将一组表格行包装成不可再与正文混合的原子单元。 */
    private Unit tableUnit(DocumentIr.Block block, int pageNumber, String text) {
        return new Unit(text, block.headingPath(), pageNumber, pageNumber, List.of(block.blockId()),
                sourceSpans(block), qualityFlags(block), DocumentIr.BlockType.TABLE);
    }

    /** 按行拼接表头、已接纳行和候选行。 */
    private String tableText(String header, List<String> rows, String addition) {
        List<String> values = new ArrayList<>();
        if (header != null && !header.isBlank()) values.add(header);
        values.addAll(rows);
        if (addition != null && !addition.isBlank()) values.add(addition);
        return String.join("\n", values);
    }

    /** 按列号恢复表格单元格的稳定展示顺序。 */
    private String rowText(DocumentIr.TableRow row) {
        return row.cells().stream().sorted(Comparator.comparingInt(DocumentIr.TableCell::columnIndex))
                .map(DocumentIr.TableCell::normalizedText)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** 在标题边界和双预算内合并正文，表格、代码、公式和图片保持原子性。 */
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

    /** 展示文本保留原貌，检索文本额外注入文档与章节上下文。 */
    private ChildDraft draft(Unit unit, DocumentIr document) {
        String title = documentTitle(document);
        String display = unit.displayText().strip();
        String embedding = embeddingText(title, unit.headingPath(), display);
        return new ChildDraft(display, embedding, unit.headingPath(), unit.pageFrom(), unit.pageTo(),
                unit.blockIds(), unit.sourceSpans(), unit.qualityFlags(), unit.type());
    }

    /** 同章节子块在父级预算内聚合，形成召回后扩展上下文。 */
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

    /** 生成稳定 ID、父子关系、相邻链和可复算元数据。 */
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

    /** 优先使用显式标题块，缺失时回退到来源文件名。 */
    private String documentTitle(DocumentIr document) {
        return document.blocks().stream()
                .filter(block -> block.type() == DocumentIr.BlockType.TITLE)
                .map(DocumentIr.Block::normalizedText).filter(value -> !value.isBlank()).findFirst()
                .orElse(document.sourceName());
    }

    /** 仅对 Embedding 副本做 NFKC，引用文本不受兼容归一化影响。 */
    private String embeddingText(String title, List<String> headings, String displayText) {
        StringBuilder result = new StringBuilder();
        if (title != null && !title.isBlank()) result.append("文档：").append(title.strip()).append('\n');
        if (headings != null && !headings.isEmpty()) {
            result.append("章节：").append(String.join(" > ", headings)).append('\n');
        }
        result.append("正文：").append(displayText);
        return Normalizer.normalize(result, Normalizer.Form.NFKC);
    }

    /** 来源跨度缺失时返回空集合，不伪造引用坐标。 */
    private List<DocumentIr.SourceSpan> sourceSpans(DocumentIr.Block block) {
        return block.sourceSpan() == null ? List.of() : List.of(block.sourceSpan());
    }

    /** 将解析质量标记原样传播到分块。 */
    private Set<String> qualityFlags(DocumentIr.Block block) {
        return block.flags().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 结构内容禁止与相邻正文合并，防止语法被截断或污染。 */
    private boolean atomic(DocumentIr.BlockType type) {
        return type == DocumentIr.BlockType.TABLE || type == DocumentIr.BlockType.CODE
                || type == DocumentIr.BlockType.FORMULA || type == DocumentIr.BlockType.IMAGE;
    }

    /** 预演加入候选子块后的父块展示文本。 */
    private String joinDisplay(List<ChildDraft> values, ChildDraft addition) {
        if (values.isEmpty()) return addition.displayText();
        return values.stream().map(ChildDraft::displayText)
                .collect(java.util.stream.Collectors.joining("\n\n")) + "\n\n" + addition.displayText();
    }

    /** 对超限内容滑窗切分，并保证游标每轮向前推进。 */
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

    /** 优先在句末或空白处落刀，找不到时使用硬边界。 */
    private int safeBoundary(String value, int start, int proposed, int consumed) {
        int minimum = Math.max(consumed + 1, start + Math.max(1, (proposed - start) / 2));
        for (int index = proposed; index >= minimum; index--) {
            char character = value.charAt(index - 1);
            if (character == '\n' || character == '。' || character == '！' || character == '？'
                    || character == '.' || character == ';' || character == ' ') return index;
        }
        return proposed;
    }

    /** 字符数与估算 Token 必须同时不超预算。 */
    private boolean fits(String value, int chars, int tokens) {
        return value.length() <= chars && approximateTokens(value) <= tokens;
    }

    /** 明确的兼容Tokenizer回退；产物会记录版本，不能被误认为真实模型Tokenizer。 */
    public static int approximateTokens(String value) {
        return StructuredRagChunker.approximateTokens(value);
    }

    /** 内容哈希和序号共同保证同一来源重跑时 ID 稳定。 */
    private String stableId(String sourceId, String level, int ordinal, String contentHash) {
        return "chk_" + sha256(sourceId + '\0' + level + '\0' + ordinal + '\0' + contentHash)
                .substring(0, 48);
    }

    /** 使用 JVM 必备的 SHA-256 生成跨进程稳定摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
        }
    }

    /** 分块层级；父块用于扩展上下文，子块用于精确召回。 */
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
        /** 返回实际参与向量召回的子块。 */
        public List<StructuredChunk> children() {
            return chunks.stream().filter(chunk -> chunk.level() == Level.CHILD).toList();
        }
    }

    /** 从 IR 提取并可连续合并的最小语义单元。 */
    private record Unit(String displayText, List<String> headingPath, int pageFrom, int pageTo,
                        List<String> blockIds, List<DocumentIr.SourceSpan> sourceSpans,
                        Set<String> qualityFlags, DocumentIr.BlockType type) {
        private Unit {
            headingPath = List.copyOf(headingPath);
            blockIds = List.copyOf(blockIds);
            sourceSpans = List.copyOf(sourceSpans);
            qualityFlags = Set.copyOf(qualityFlags);
        }
        /** 保留全部溯源信息，仅替换超限切片正文。 */
        private Unit withDisplayText(String value) {
            return new Unit(value, headingPath, pageFrom, pageTo, blockIds, sourceSpans, qualityFlags, type);
        }
        /** 合并相邻单元，同时汇总页码、块 ID、来源跨度和质量标记。 */
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

    /** 已生成展示文本和检索文本、尚未分配稳定 ID 的子块。 */
    private record ChildDraft(String displayText, String embeddingText, List<String> headingPath,
                              int pageFrom, int pageTo, List<String> blockIds,
                              List<DocumentIr.SourceSpan> sourceSpans, Set<String> qualityFlags,
                              DocumentIr.BlockType type) {
    }

    /** 同章节且同父级预算内的子块集合。 */
    private record ParentGroup(List<ChildDraft> children) {
        /** 父块起始页取全部子块最小值。 */
        private int pageFrom() { return children.stream().mapToInt(ChildDraft::pageFrom).min().orElse(1); }
        /** 父块结束页取全部子块最大值。 */
        private int pageTo() { return children.stream().mapToInt(ChildDraft::pageTo).max().orElse(1); }
        /** 去重汇总父块覆盖的原始块。 */
        private List<String> blockIds() { return children.stream().flatMap(value -> value.blockIds().stream()).distinct().toList(); }
        /** 保留全部来源跨度供引用定位。 */
        private List<DocumentIr.SourceSpan> sourceSpans() { return children.stream().flatMap(value -> value.sourceSpans().stream()).toList(); }
        /** 合并子块质量标记供召回阶段过滤。 */
        private Set<String> qualityFlags() {
            return children.stream().flatMap(value -> value.qualityFlags().stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /** 等待补齐相邻指针与最终元数据的分块中间态。 */
    private record Partial(Level level, String chunkId, String parentChunkId, String displayText,
                           String embeddingText, int tokenCount, int pageFrom, int pageTo,
                           List<String> headingPath, List<String> blockIds,
                           List<DocumentIr.SourceSpan> sourceSpans, String contentHash,
                           Set<String> qualityFlags, DocumentIr.BlockType type) {
    }
}
