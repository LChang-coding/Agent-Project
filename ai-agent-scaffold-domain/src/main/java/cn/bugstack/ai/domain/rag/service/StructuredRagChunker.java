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

    public static final String CHUNKER_VERSION = "structured-java-v1";
    public static final String TOKENIZER_VERSION = "approx-unicode-v1";
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

    private boolean fits(String value, int chars, int tokens) {
        return value.length() <= chars && approximateTokens(value) <= tokens;
    }

    private boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length && lines[index].contains("|")
                && TABLE_SEPARATOR.matcher(lines[index + 1]).matches();
    }

    private Block block(String content, String heading, Integer page, BlockType type) {
        return new Block(content.trim(), heading, page, type);
    }

    private String join(String[] lines, int from, int to) {
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, to)).trim();
    }

    private String path(List<String> headings) { return headings.isEmpty() ? null : String.join(" > ", headings); }
    private boolean same(String left, String right) { return java.util.Objects.equals(left, right); }
    private String joinDrafts(List<Draft> values, Draft addition) {
        if (values.isEmpty()) return addition.content();
        return values.stream().map(Draft::content).collect(java.util.stream.Collectors.joining("\n\n"))
                + "\n\n" + addition.content();
    }

    private String stableId(String sourceId, String level, int ordinal, String contentHash) {
        return "chk_" + sha256(sourceId + '\0' + level + '\0' + ordinal + '\0' + contentHash).substring(0, 48);
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JVM缺少SHA-256", e); }
    }

    public enum Level { PARENT, CHILD }
    private enum BlockType { PARAGRAPH, LIST, TABLE, CODE }
    private record Block(String content, String heading, Integer page, BlockType type) {}
    private record Draft(String content, String heading, Integer page, BlockType type) {}
    private record ParentGroup(List<Draft> children) {
        String content() { return children.stream().map(Draft::content)
                .collect(java.util.stream.Collectors.joining("\n\n")); }
    }
    private record Partial(Level level, String chunkId, String parentChunkId, String content,
                           int tokenCount, Integer page, String heading, String contentHash) {}

    public record Config(int childMaxChars, int childMaxTokens, int parentMaxChars,
                         int parentMaxTokens, int overlapChars) {
        public Config {
            if (childMaxChars < 16 || childMaxTokens < 4 || parentMaxChars < childMaxChars
                    || parentMaxTokens < childMaxTokens || overlapChars < 0
                    || overlapChars >= childMaxChars / 2) {
                throw new IllegalArgumentException("分块字符、Token或重叠配置非法");
            }
        }
        public static Config defaults() { return new Config(1800, 480, 6000, 1500, 160); }
    }

    public record StructuredChunk(Level level, String chunkId, String parentChunkId,
                                  String previousChunkId, String nextChunkId, int chunkIndex,
                                  String content, int tokenCount, Integer pageNumber,
                                  String headingPath, String contentHash, Map<String, String> metadata) {}
    public record ChunkingResult(List<StructuredChunk> chunks) {
        public ChunkingResult { chunks = chunks == null ? List.of() : List.copyOf(chunks); }
        public List<StructuredChunk> children() {
            return chunks.stream().filter(chunk -> chunk.level() == Level.CHILD).toList();
        }
        public List<StructuredChunk> parents() {
            return chunks.stream().filter(chunk -> chunk.level() == Level.PARENT).toList();
        }
    }
}
