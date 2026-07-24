package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Block;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.BlockType;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Flag;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Table;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.TableCell;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.TableRow;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规范文档IR的可逆清洗管线。
 * <p>每一步都生成新IR并保留变更前后镜像，重复内容仅标记抑制，不从页面中删除。</p>
 */
public final class DocumentIrCleaner {

    private final List<CleaningRule> rules;

    /**
     * 创建清洗管线。
     *
     * @param rules 按顺序执行的清洗规则
     */
    public DocumentIrCleaner(List<CleaningRule> rules) {
        if (rules == null || rules.isEmpty() || rules.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("清洗规则不能为空");
        }
        this.rules = List.copyOf(rules);
    }

    /**
     * 创建平台标准清洗管线。
     *
     * @return 使用确定性规则的清洗器
     */
    public static DocumentIrCleaner standard() {
        return new DocumentIrCleaner(List.of(
                new TextHygieneRule(),
                new RepeatedFurnitureRule(),
                new DuplicateBlockRule(),
                new ContentAnnotationRule()
        ));
    }

    /**
     * 顺序执行清洗规则。
     *
     * @param document 原始IR
     * @return 清洗后的新IR
     */
    public DocumentIr clean(DocumentIr document) {
        return cleanWithAudit(document).document();
    }

    /**
     * 顺序执行清洗规则并返回每一步的数量与耗时审计。
     *
     * @param document 原始IR
     * @return 清洗结果和不可变审计记录
     */
    public CleaningResult cleanWithAudit(DocumentIr document) {
        DocumentIr current = Objects.requireNonNull(document, "document不能为空");
        List<CleaningAudit> audits = new ArrayList<>();
        for (CleaningRule rule : rules) {
            DocumentIr before = current;
            long started = System.nanoTime();
            current = Objects.requireNonNull(rule.clean(current), rule.name() + "返回了空IR");
            Map<String, Block> beforeById = before.blocks().stream()
                    .collect(java.util.stream.Collectors.toMap(Block::blockId, block -> block));
            int modified = 0;
            int newlySuppressed = 0;
            for (Block block : current.blocks()) {
                Block previous = beforeById.get(block.blockId());
                if (previous != null && !previous.equals(block)) modified++;
                if (previous != null && !previous.suppressed() && block.suppressed()) newlySuppressed++;
            }
            long costMs = Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(System.nanoTime() - started));
            audits.add(new CleaningAudit(rule.name(), "1", before.blocks().size(),
                    current.blocks().size(), modified, newlySuppressed, costMs));
        }
        return new CleaningResult(current, audits);
    }

    /**
     * 根据rawText和清洗轨迹恢复清洗前内容。
     *
     * @param document 已清洗IR
     * @return 恢复原文和初始抑制状态的新IR
     */
    public DocumentIr restoreOriginal(DocumentIr document) {
        Objects.requireNonNull(document, "document不能为空");
        List<DocumentIr.Page> pages = document.pages().stream()
                .map(page -> page.withBlocks(page.blocks().stream().map(this::restoreBlock).toList()))
                .toList();
        Set<Flag> documentFlags = new LinkedHashSet<>(document.flags());
        document.blocks().stream().flatMap(block -> block.cleaningChanges().stream())
                .flatMap(change -> change.addedFlags().stream()).forEach(documentFlags::remove);
        return document.withPages(pages, documentFlags);
    }

    private Block restoreBlock(Block block) {
        if (block.cleaningChanges().isEmpty()) {
            return block;
        }
        Set<Flag> flags = new LinkedHashSet<>(block.flags());
        block.cleaningChanges().stream().flatMap(change -> change.addedFlags().stream()).forEach(flags::remove);
        boolean suppressed = block.cleaningChanges().get(0).suppressedBefore();
        if (!suppressed) {
            flags.remove(Flag.SUPPRESSED);
        }
        Table restoredTable = restoreTable(block.table());
        return new Block(block.blockId(), block.type(), block.rawText(), block.rawText(),
                block.sourceSpan(), block.boundingBox(), restoredTable, block.readingOrder(),
                block.regionId(), block.columnIndex(), block.headingPath(), block.language(),
                block.confidence(), flags, suppressed, !suppressed,
                suppressed ? originalSuppressionReason(block) : "", List.of());
    }

    private String originalSuppressionReason(Block block) {
        String reason = block.cleaningChanges().get(0).suppressionReasonBefore();
        return reason.isBlank() ? "original_suppression" : reason;
    }

    private Table restoreTable(Table table) {
        if (table == null) return null;
        return new Table(table.rows().stream().map(row -> new TableRow(row.rowIndex(),
                row.cells().stream().map(cell -> new TableCell(cell.rowIndex(), cell.columnIndex(),
                        cell.rowSpan(), cell.columnSpan(), cell.rawText(), cell.rawText(),
                        cell.sourceSpan(), cell.boundingBox(), cell.flags())).toList())).toList());
    }

    /**
     * 单个不可变清洗步骤。
     */
    public interface CleaningRule {

        /**
         * 返回规则稳定名称。
         *
         * @return 清洗器名称
         */
        String name();

        /**
         * 清洗文档并返回新IR。
         *
         * @param document 输入IR
         * @return 输出IR
         */
        DocumentIr clean(DocumentIr document);
    }

    /** 完整清洗结果。 */
    public record CleaningResult(DocumentIr document, List<CleaningAudit> audits) {
        public CleaningResult {
            document = Objects.requireNonNull(document, "document不能为空");
            audits = List.copyOf(Objects.requireNonNullElse(audits, List.of()));
        }
    }

    /** 单个清洗步骤的安全审计摘要。 */
    public record CleaningAudit(String cleanerName, String cleanerVersion,
                                int inputBlocks, int outputBlocks, int modifiedBlocks,
                                int suppressedBlocks, long costMs) {
        public CleaningAudit {
            if (cleanerName == null || cleanerName.isBlank() || cleanerVersion == null
                    || cleanerVersion.isBlank() || inputBlocks < 0 || outputBlocks < 0
                    || modifiedBlocks < 0 || suppressedBlocks < 0 || costMs < 0) {
                throw new IllegalArgumentException("清洗审计参数非法");
            }
        }
    }

    private abstract static class BlockRule implements CleaningRule {

        @Override
        public DocumentIr clean(DocumentIr document) {
            List<DocumentIr.Page> pages = document.pages().stream()
                    .map(page -> page.withBlocks(page.blocks().stream()
                            .map(block -> cleanBlock(document, page, block)).toList()))
                    .toList();
            return document.withPages(pages, collectFlags(document.flags(), pages));
        }

        protected abstract Block cleanBlock(DocumentIr document, DocumentIr.Page page, Block block);
    }

    private static final class TextHygieneRule extends BlockRule {

        private static final Pattern DEHYPHENATION = Pattern.compile("(?<=\\p{L})-[\\t ]*\\R[\\t ]*(?=\\p{Ll})");

        @Override
        public String name() {
            return "text_hygiene";
        }

        @Override
        protected Block cleanBlock(DocumentIr document, DocumentIr.Page page, Block block) {
            TextResult result = cleanText(block.normalizedText());
            Table table = cleanTable(block.table());
            if (result.flags().isEmpty() && Objects.equals(table, block.table())) return block;
            return block.evolve(name(), result.text(), table, result.flags(),
                    block.suppressed(), block.suppressionReason());
        }

        private Table cleanTable(Table table) {
            if (table == null) return null;
            return new Table(table.rows().stream().map(row -> new TableRow(row.rowIndex(),
                    row.cells().stream().map(this::cleanCell).toList())).toList());
        }

        private TableCell cleanCell(TableCell cell) {
            TextResult result = cleanText(cell.normalizedText());
            Set<Flag> flags = new LinkedHashSet<>(cell.flags());
            flags.addAll(result.flags());
            return new TableCell(cell.rowIndex(), cell.columnIndex(), cell.rowSpan(), cell.columnSpan(),
                    cell.rawText(), result.text(), cell.sourceSpan(), cell.boundingBox(), flags);
        }

        private TextResult cleanText(String source) {
            String value = source == null ? "" : source;
            Set<Flag> flags = new LinkedHashSet<>();
            // 展示与引用正文只使用NFC，避免NFKC改变公式、全角编号和兼容字符；
            // NFKC仅在后续生成embeddingText时应用到检索副本。
            String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
            if (!normalized.equals(value)) flags.add(Flag.UNICODE_NORMALIZED);
            StringBuilder filtered = new StringBuilder(normalized.length());
            normalized.codePoints().forEach(codePoint -> {
                if (isZeroWidth(codePoint)) {
                    flags.add(Flag.ZERO_WIDTH_CHARACTER);
                } else if (isDiscardedControl(codePoint)) {
                    flags.add(Flag.CONTROL_CHARACTER);
                } else {
                    filtered.appendCodePoint(codePoint);
                }
            });
            String cleaned = filtered.toString().replace("\r\n", "\n").replace('\r', '\n');
            String joined = DEHYPHENATION.matcher(cleaned).replaceAll("");
            if (!joined.equals(cleaned)) flags.add(Flag.DEHYPHENATED);
            if (joined.indexOf('\uFFFD') >= 0) flags.add(Flag.REPLACEMENT_CHARACTER);
            return new TextResult(joined, flags);
        }

        private boolean isZeroWidth(int codePoint) {
            return codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
                    || codePoint == 0x2060 || codePoint == 0xFEFF;
        }

        private boolean isDiscardedControl(int codePoint) {
            return Character.getType(codePoint) == Character.CONTROL
                    && codePoint != '\n' && codePoint != '\t';
        }
    }

    private static final class RepeatedFurnitureRule implements CleaningRule {

        @Override
        public String name() {
            return "repeated_page_furniture";
        }

        @Override
        public DocumentIr clean(DocumentIr document) {
            Map<String, Set<Integer>> occurrences = new LinkedHashMap<>();
            document.pages().forEach(page -> page.blocks().stream().filter(this::candidate)
                    .forEach(block -> occurrences.computeIfAbsent(fingerprint(block.normalizedText()),
                            ignored -> new LinkedHashSet<>()).add(page.pageNumber())));
            int threshold = Math.max(2, (int) Math.ceil(document.pages().size() * 0.5));
            Set<String> repeated = occurrences.entrySet().stream()
                    .filter(entry -> !entry.getKey().isBlank() && entry.getValue().size() >= threshold)
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<DocumentIr.Page> pages = document.pages().stream().map(page -> page.withBlocks(
                    page.blocks().stream().map(block -> suppress(block, repeated)).toList())).toList();
            return document.withPages(pages, collectFlags(document.flags(), pages));
        }

        private boolean candidate(Block block) {
            return switch (block.type()) {
                case HEADER, FOOTER, PAGE_NUMBER, WATERMARK -> true;
                default -> false;
            };
        }

        private Block suppress(Block block, Set<String> repeated) {
            if (!candidate(block) || !repeated.contains(fingerprint(block.normalizedText()))) return block;
            Flag flag = block.type() == BlockType.FOOTER || block.type() == BlockType.PAGE_NUMBER
                    ? Flag.REPEATED_FOOTER : Flag.REPEATED_HEADER;
            return block.apply(name(), block.normalizedText(), Set.of(flag), true, name());
        }
    }

    private static final class DuplicateBlockRule implements CleaningRule {

        @Override
        public String name() {
            return "duplicate_block";
        }

        @Override
        public DocumentIr clean(DocumentIr document) {
            Set<String> seen = new LinkedHashSet<>();
            List<DocumentIr.Page> pages = new ArrayList<>();
            for (DocumentIr.Page page : document.pages()) {
                List<Block> blocks = new ArrayList<>();
                for (Block block : page.blocks()) {
                    String fingerprint = fingerprint(block.normalizedText());
                    if (!block.suppressed() && fingerprint.length() >= 20 && !seen.add(fingerprint)) {
                        blocks.add(block.apply(name(), block.normalizedText(),
                                Set.of(Flag.DUPLICATE_BLOCK), true, name()));
                    } else {
                        blocks.add(block);
                    }
                }
                pages.add(page.withBlocks(blocks));
            }
            return document.withPages(pages, collectFlags(document.flags(), pages));
        }
    }

    private static final class ContentAnnotationRule extends BlockRule {

        private static final Pattern SENSITIVE = Pattern.compile(
                "(?iu)([\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)|\\b\\d{15,18}[0-9xX]\\b)");
        private static final Pattern PROMPT_INJECTION = Pattern.compile(
                "(?iu)(ignore\\s+(all\\s+)?previous\\s+instructions?|reveal\\s+(the\\s+)?system\\s+prompt"
                        + "|<\\s*system\\s*>|忽略.{0,12}(指令|规则|提示)|泄露.{0,8}(系统提示|提示词))");

        @Override
        public String name() {
            return "content_annotation";
        }

        @Override
        protected Block cleanBlock(DocumentIr document, DocumentIr.Page page, Block block) {
            Set<Flag> flags = new LinkedHashSet<>();
            String text = block.normalizedText();
            if (SENSITIVE.matcher(text).find()) flags.add(Flag.SENSITIVE_CONTENT);
            if (PROMPT_INJECTION.matcher(text).find()) flags.add(Flag.PROMPT_INJECTION);
            if (languageMismatch(document.detectedLanguage(), block.language(), text)) {
                flags.add(Flag.LANGUAGE_MISMATCH);
            }
            if (flags.isEmpty()) return block;
            return block.apply(name(), text, flags, block.suppressed(), block.suppressionReason());
        }

        private boolean languageMismatch(String documentLanguage, String blockLanguage, String text) {
            String expected = !"und".equalsIgnoreCase(blockLanguage) ? blockLanguage : documentLanguage;
            if (expected == null || expected.equalsIgnoreCase("und")) return false;
            long letters = text.codePoints().filter(Character::isLetter).count();
            if (letters < 12) return false;
            long han = text.codePoints().filter(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN).count();
            String language = expected.toLowerCase(Locale.ROOT);
            if (language.startsWith("zh")) return han / (double) letters < 0.20;
            if (language.startsWith("en")) return han / (double) letters > 0.20;
            return false;
        }
    }

    private static Set<Flag> collectFlags(Set<Flag> original, List<DocumentIr.Page> pages) {
        Set<Flag> result = new LinkedHashSet<>(original);
        pages.stream().flatMap(page -> page.blocks().stream())
                .flatMap(block -> block.flags().stream()).forEach(result::add);
        return result;
    }

    private static String fingerprint(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record TextResult(String text, Set<Flag> flags) {
        private TextResult {
            flags = Set.copyOf(flags);
        }
    }
}
