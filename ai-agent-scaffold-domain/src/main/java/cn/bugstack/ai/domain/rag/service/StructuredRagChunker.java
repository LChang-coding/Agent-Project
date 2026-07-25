package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.port.RagDocumentParserPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 结构优先、预算受控的确定性 Parent/Child 分块器。 */
public final class StructuredRagChunker {

    /** 算法、Token 估算和 Markdown 结构识别的稳定版本标识。 */
    public static final String CHUNKER_VERSION = "structured-java-v1";
    public static final String TOKENIZER_VERSION = "approx-unicode-v1";
    /** 结构模式只识别语法明确的 Markdown 标题、列表和表格分隔行。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-*+] |\\d+[.)] ).+");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$");

    /** 按 Docling/本地解析端口的规范化 Markdown 和结构章节切分。 */
    public ChunkingResult chunk(String sourceId, RagDocumentParserPort.ParsedDocument document, Config config) {
        if (document == null) throw new IllegalArgumentException("解析文档不能为空");
        return chunk(sourceId, document.normalizedMarkdown(), document.sections(), config);
    }

    /** 切分规范化 Markdown/纯文本；空文本返回空结果。 */
    public ChunkingResult chunk(String sourceId, String normalizedText,
                                List<RagDocumentParserPort.ParsedSection> sections, Config config) {
        if (sourceId == null || sourceId.isBlank() || config == null) {
            throw new IllegalArgumentException("分块来源或配置不能为空");
        }
        if (normalizedText == null || normalizedText.isBlank()) return new ChunkingResult(List.of());
        List<Block> blocks = new ArrayList<>();
        if (sections != null && !sections.isEmpty()) {
            sections.stream().sorted(java.util.Comparator.comparingInt(RagDocumentParserPort.ParsedSection::order))
                    .forEach(section -> blocks.addAll(parseBlocks(section.content(), section.headingPath(), section.pageNumber())));
        } else {
            blocks.addAll(parseBlocks(normalizedText, null, null));
        }
        List<Draft> children = childDrafts(blocks, config);
        if (children.isEmpty()) return new ChunkingResult(List.of());
        List<ParentGroup> groups = parentGroups(children, config);
        return materialize(sourceId, groups);
    }

    /** 将文本解析为标题路径明确的段落、列表、表格和代码块。 */
    private List<Block> parseBlocks(String text, String inheritedHeading, Integer page) {
        List<Block> result = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        if (inheritedHeading != null && !inheritedHeading.isBlank()) headings.add(inheritedHeading.trim());
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length;) {
            String line = lines[i];
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                while (headings.size() >= level) headings.remove(headings.size() - 1);
                headings.add(heading.group(2).trim());
                i++;
                continue;
            }
            if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                String fence = line.trim().substring(0, 3);
                int end = i + 1;
                while (end < lines.length && !lines[end].trim().startsWith(fence)) end++;
                if (end < lines.length) end++;
                result.add(block(join(lines, i, end), path(headings), page, BlockType.CODE));
                i = end;
                continue;
            }
            if (isTableStart(lines, i)) {
                int end = i + 1;
                while (end < lines.length && lines[end].contains("|") && !lines[end].isBlank()) end++;
                result.add(block(join(lines, i, end), path(headings), page, BlockType.TABLE));
                i = end;
                continue;
            }
            if (LIST.matcher(line).matches()) {
                int end = i + 1;
                while (end < lines.length && (LIST.matcher(lines[end]).matches()
                        || !lines[end].isBlank() && Character.isWhitespace(lines[end].charAt(0)))) end++;
                result.add(block(join(lines, i, end), path(headings), page, BlockType.LIST));
                i = end;
                continue;
            }
            if (line.isBlank()) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < lines.length && !lines[end].isBlank() && !HEADING.matcher(lines[end]).matches()
                    && !lines[end].trim().startsWith("```") && !lines[end].trim().startsWith("~~~")
                    && !LIST.matcher(lines[end]).matches() && !isTableStart(lines, end)) end++;
            result.add(block(join(lines, i, end), path(headings), page, BlockType.PARAGRAPH));
            i = end;
        }
        return result;
    }

    /** 在结构边界与双预算内生成子块草稿。 */
    private List<Draft> childDrafts(List<Block> blocks, Config config) {
        List<Draft> result = new ArrayList<>();
        Draft current = null;
        for (Block block : blocks) {
            if (block.content().isBlank()) continue;
            if (!fits(block.content(), config.childMaxChars(), config.childMaxTokens())) {
                if (current != null) { result.add(current); current = null; }
                for (String part : splitOversized(block.content(), config.childMaxChars(),
                        config.childMaxTokens(), config.overlapChars())) {
                    result.add(new Draft(part, block.heading(), block.page(), block.type()));
                }
                continue;
            }
            String combined = current == null ? block.content() : current.content() + "\n\n" + block.content();
            boolean structuralBoundary = block.type() == BlockType.CODE || block.type() == BlockType.TABLE
                    || current != null && (current.type() == BlockType.CODE || current.type() == BlockType.TABLE)
                    || current != null && !same(current.heading(), block.heading());
            if (current != null && (structuralBoundary
                    || !fits(combined, config.childMaxChars(), config.childMaxTokens()))) {
                result.add(current);
                current = null;
            }
            current = current == null ? new Draft(block.content(), block.heading(), block.page(), block.type())
                    : new Draft(combined, current.heading(), current.page(), current.type());
        }
        if (current != null) result.add(current);
        return result;
    }

    /** 只合并同章节且未超过父级预算的相邻子块。 */
    private List<ParentGroup> parentGroups(List<Draft> children, Config config) {
        List<ParentGroup> groups = new ArrayList<>();
        List<Draft> current = new ArrayList<>();
        for (Draft child : children) {
            String combined = joinDrafts(current, child);
            if (!current.isEmpty() && (!same(current.get(0).heading(), child.heading())
                    || !fits(combined, config.parentMaxChars(), config.parentMaxTokens()))) {
                groups.add(new ParentGroup(List.copyOf(current)));
                current.clear();
            }
            current.add(child);
        }
        if (!current.isEmpty()) groups.add(new ParentGroup(List.copyOf(current)));
        return groups;
    }

    /** 生成稳定父子 ID，并串联相邻子块供上下文扩展。 */
    private ChunkingResult materialize(String sourceId, List<ParentGroup> groups) {
        List<Partial> partials = new ArrayList<>();
        int childOrdinal = 0;
        for (int parentOrdinal = 0; parentOrdinal < groups.size(); parentOrdinal++) {
            ParentGroup group = groups.get(parentOrdinal);
            String parentContent = group.content();
            String parentHash = sha256(parentContent);
            String parentId = stableId(sourceId, "parent", parentOrdinal, parentHash);
            partials.add(new Partial(Level.PARENT, parentId, null, parentContent,
                    approximateTokens(parentContent), group.children().get(0).page(),
                    group.children().get(0).heading(), parentHash));
            for (Draft child : group.children()) {
                String childHash = sha256(child.content());
                partials.add(new Partial(Level.CHILD,
                        stableId(sourceId, "child", childOrdinal++, childHash), parentId,
                        child.content(), approximateTokens(child.content()), child.page(), child.heading(), childHash));
            }
        }
        List<Integer> childPositions = new ArrayList<>();
        for (int i = 0; i < partials.size(); i++) if (partials.get(i).level() == Level.CHILD) childPositions.add(i);
        Map<Integer, String> previous = new LinkedHashMap<>();
        Map<Integer, String> next = new LinkedHashMap<>();
        for (int i = 0; i < childPositions.size(); i++) {
            int position = childPositions.get(i);
            if (i > 0) previous.put(position, partials.get(childPositions.get(i - 1)).chunkId());
            if (i + 1 < childPositions.size()) next.put(position, partials.get(childPositions.get(i + 1)).chunkId());
        }
        List<StructuredChunk> result = new ArrayList<>();
        for (int i = 0; i < partials.size(); i++) {
            Partial value = partials.get(i);
            result.add(new StructuredChunk(value.level(), value.chunkId(), value.parentChunkId(), previous.get(i),
                    next.get(i), i, value.content(), value.tokenCount(), value.page(), value.heading(),
                    value.contentHash(), Map.of("chunker_version", CHUNKER_VERSION,
                    "tokenizer_version", TOKENIZER_VERSION, "chunk_level", value.level().name().toLowerCase())));
        }
        return new ChunkingResult(result);
    }

    /** 对超限结构块滑窗切分，同时保护 Unicode 代理对。 */
    private List<String> splitOversized(String content, int maxChars, int maxTokens, int overlapChars) {
        List<String> parts = new ArrayList<>();
        int cursor = 0;
        while (cursor < content.length()) {
            int start = parts.isEmpty() ? cursor : Math.max(0, cursor - overlapChars);
            int hardEnd = Math.min(content.length(), start + maxChars);
            if (hardEnd < content.length() && Character.isLowSurrogate(content.charAt(hardEnd))) hardEnd--;
            int end = maxTokenEnd(content, start, hardEnd, maxTokens);
            if (end <= cursor) {
                start = cursor;
                hardEnd = Math.min(content.length(), start + maxChars);
                if (hardEnd < content.length() && Character.isLowSurrogate(content.charAt(hardEnd))) hardEnd--;
                end = maxTokenEnd(content, start, hardEnd, maxTokens);
            }
            end = safeBoundary(content, start, end, cursor);
            String part = content.substring(start, end).trim();
            if (!part.isBlank()) parts.add(part);
            cursor = end;
        }
        return parts;
    }

    /** 二分求出满足 Token 预算的最大字符边界。 */
    private int maxTokenEnd(String value, int start, int hardEnd, int maxTokens) {
        int low = Math.min(start + 1, hardEnd), high = hardEnd, best = start;
        while (low <= high) {
            int rawMiddle = (low + high) >>> 1;
            int middle = rawMiddle;
            if (middle < value.length() && Character.isLowSurrogate(value.charAt(middle))) middle--;
            if (approximateTokens(value.substring(start, middle)) <= maxTokens) {
                best = middle; low = rawMiddle + 1;
            } else high = rawMiddle - 1;
        }
        return best;
    }

    /** 优先在自然断句处切分，并确保边界越过已消费位置。 */
    private int safeBoundary(String value, int start, int proposed, int consumedThrough) {
        int minimum = Math.max(consumedThrough + 1, start + Math.max(1, (proposed - start) / 2));
        for (int i = proposed; i >= minimum; i--) {
            char c = value.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == ';' || c == ' ') return i;
        }
        return proposed;
    }

    /** 估算 Token：CJK/标点按一个，ASCII 连续词按每四字符一个。 */
    public static int approximateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        int tokens = 0, asciiRun = 0;
        for (int codePoint : value.codePoints().toArray()) {
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                asciiRun++;
            } else {
                if (asciiRun > 0) { tokens += (asciiRun + 3) / 4; asciiRun = 0; }
                if (!Character.isWhitespace(codePoint)) tokens++;
            }
        }
        if (asciiRun > 0) tokens += (asciiRun + 3) / 4;
        return tokens;
    }

    /** 字符数和估算 Token 必须同时不超预算。 */
    private boolean fits(String value, int chars, int tokens) {
        return value.length() <= chars && approximateTokens(value) <= tokens;
    }

    /** Markdown 表头后必须紧跟合法分隔行才识别为表格。 */
    private boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length && lines[index].contains("|")
                && TABLE_SEPARATOR.matcher(lines[index + 1]).matches();
    }

    /** 去除结构块首尾空白后保存其章节与页码来源。 */
    private Block block(String content, String heading, Integer page, BlockType type) {
        return new Block(content.trim(), heading, page, type);
    }

    /** 按原换行连接半开区间内的源文本。 */
    private String join(String[] lines, int from, int to) {
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, to)).trim();
    }

    /** 将标题栈压平为稳定章节路径。 */
    private String path(List<String> headings) { return headings.isEmpty() ? null : String.join(" > ", headings); }
    /** 空值安全地判断两个章节路径是否一致。 */
    private boolean same(String left, String right) { return java.util.Objects.equals(left, right); }
    /** 预演追加子块后的父块正文。 */
    private String joinDrafts(List<Draft> values, Draft addition) {
        if (values.isEmpty()) return addition.content();
        return values.stream().map(Draft::content).collect(java.util.stream.Collectors.joining("\n\n"))
                + "\n\n" + addition.content();
    }

    /** 来源、层级、顺序和内容共同决定可复现的分块 ID。 */
    private String stableId(String sourceId, String level, int ordinal, String contentHash) {
        return "chk_" + sha256(sourceId + '\0' + level + '\0' + ordinal + '\0' + contentHash).substring(0, 48);
    }
    /** 使用 SHA-256 生成跨进程稳定摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JVM缺少SHA-256", e); }
    }

    /** 父块承载扩展上下文，子块承担精确召回。 */
    public enum Level { PARENT, CHILD }
    /** 影响合并边界的 Markdown 结构类型。 */
    private enum BlockType { PARAGRAPH, LIST, TABLE, CODE }
    /** 从 Markdown 解析出的最小结构块。 */
    private record Block(String content, String heading, Integer page, BlockType type) {}
    /** 尚未分配稳定 ID 的子块。 */
    private record Draft(String content, String heading, Integer page, BlockType type) {}
    /** 同章节、同父级预算内的子块集合。 */
    private record ParentGroup(List<Draft> children) {
        String content() { return children.stream().map(Draft::content)
                .collect(java.util.stream.Collectors.joining("\n\n")); }
    }
    /** 等待补齐相邻关系的分块中间态。 */
    private record Partial(Level level, String chunkId, String parentChunkId, String content,
                           int tokenCount, Integer page, String heading, String contentHash) {}

    /** 父子分块的字符、Token 与重叠预算。 */
    public record Config(int childMaxChars, int childMaxTokens, int parentMaxChars,
                         int parentMaxTokens, int overlapChars) {
        public Config {
            if (childMaxChars < 16 || childMaxTokens < 4 || parentMaxChars < childMaxChars
                    || parentMaxTokens < childMaxTokens || overlapChars < 0
                    || overlapChars >= childMaxChars / 2) {
                throw new IllegalArgumentException("分块字符、Token或重叠配置非法");
            }
        }
        /** 返回平台默认的父子预算与重叠窗口。 */
        public static Config defaults() { return new Config(1800, 480, 6000, 1500, 160); }
    }

    /** 对外暴露的结构化分块及其溯源元数据。 */
    public record StructuredChunk(Level level, String chunkId, String parentChunkId,
                                  String previousChunkId, String nextChunkId, int chunkIndex,
                                  String content, int tokenCount, Integer pageNumber,
                                  String headingPath, String contentHash, Map<String, String> metadata) {}
    /** 完整分块结果，内部复制列表以阻断外部修改。 */
    public record ChunkingResult(List<StructuredChunk> chunks) {
        public ChunkingResult { chunks = chunks == null ? List.of() : List.copyOf(chunks); }
        /** 返回承担向量召回的子块。 */
        public List<StructuredChunk> children() {
            return chunks.stream().filter(chunk -> chunk.level() == Level.CHILD).toList();
        }
        /** 返回用于扩展上下文的父块。 */
        public List<StructuredChunk> parents() {
            return chunks.stream().filter(chunk -> chunk.level() == Level.PARENT).toList();
        }
    }
}
