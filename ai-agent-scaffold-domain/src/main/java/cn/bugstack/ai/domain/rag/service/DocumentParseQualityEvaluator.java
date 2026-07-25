package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.document.DocumentIr;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Block;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.BlockType;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.Flag;
import cn.bugstack.ai.domain.rag.model.document.DocumentIr.SourceSpan;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport.Finding;
import cn.bugstack.ai.domain.rag.model.document.DocumentParseQualityReport.Severity;
import cn.bugstack.ai.domain.rag.model.document.DocumentQualityDisposition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于规范IR生成确定性的文档解析质量报告。
 */
public final class DocumentParseQualityEvaluator {

    /** 各维度权重总和固定为 1，保证总分仍落在零到一之间。 */
    private static final double COVERAGE_WEIGHT = 0.25;
    private static final double ORDER_WEIGHT = 0.15;
    private static final double OCR_WEIGHT = 0.15;
    private static final double TABLE_WEIGHT = 0.15;
    private static final double DUPLICATE_WEIGHT = 0.15;
    private static final double REPLACEMENT_WEIGHT = 0.15;

    private final Config config;

    /**
     * 创建质量评估器。
     *
     * @param config 处置阈值
     */
    public DocumentParseQualityEvaluator(Config config) {
        this.config = Objects.requireNonNull(config, "config不能为空");
    }

    /**
     * 创建平台标准评估器。
     *
     * @return 标准阈值评估器
     */
    public static DocumentParseQualityEvaluator standard() {
        return new DocumentParseQualityEvaluator(new Config(0.90, 0.70, 0.45, 0.35, 0.50));
    }

    /**
     * 评估文档覆盖、顺序、OCR、表格、重复和替换字符质量。
     *
     * @param document 待评估IR
     * @return 不可变质量报告
     */
    public DocumentParseQualityReport evaluate(DocumentIr document) {
        Objects.requireNonNull(document, "document不能为空");
        List<Finding> findings = new ArrayList<>();
        double coverage = coverage(document, findings);
        double order = order(document, findings);
        double ocr = ocr(document, findings);
        double table = table(document, findings);
        double duplicate = duplicate(document, findings);
        double replacement = replacement(document, findings);
        annotateRisks(document, findings);
        double overall = document.blocks().isEmpty() ? 0
                : round(coverage * COVERAGE_WEIGHT + order * ORDER_WEIGHT + ocr * OCR_WEIGHT
                + table * TABLE_WEIGHT + duplicate * DUPLICATE_WEIGHT + replacement * REPLACEMENT_WEIGHT);
        DocumentQualityDisposition disposition = disposition(document, coverage, order, ocr, table,
                duplicate, replacement, overall, findings);
        return new DocumentParseQualityReport(round(coverage), round(order), round(ocr), round(table),
                round(duplicate), round(replacement), overall, disposition, findings);
    }

    /** 合并同一来源的字符区间，衡量解析结果对原文的覆盖程度。 */
    private double coverage(DocumentIr document, List<Finding> findings) {
        Map<String, List<SourceSpan>> bySource = new LinkedHashMap<>();
        document.blocks().stream().map(Block::sourceSpan).filter(Objects::nonNull)
                .forEach(span -> bySource.computeIfAbsent(span.sourceLocation(), ignored -> new ArrayList<>()).add(span));
        if (bySource.isEmpty()) {
            findings.add(finding("SOURCE_SPAN_MISSING", Severity.WARNING,
                    "解析结果没有来源字符区间", document.blocks()));
            return document.blocks().isEmpty() ? 0 : 0.5;
        }
        long covered = 0;
        long expected = 0;
        for (List<SourceSpan> spans : bySource.values()) {
            List<SourceSpan> sorted = spans.stream().sorted(Comparator.comparingInt(SourceSpan::startOffset)
                    .thenComparingInt(SourceSpan::endOffset)).toList();
            int start = sorted.get(0).startOffset();
            int end = sorted.get(0).endOffset();
            expected += sorted.stream().mapToInt(SourceSpan::endOffset).max().orElse(0);
            for (int index = 1; index < sorted.size(); index++) {
                SourceSpan span = sorted.get(index);
                if (span.startOffset() > end) {
                    covered += end - start;
                    start = span.startOffset();
                    end = span.endOffset();
                } else {
                    end = Math.max(end, span.endOffset());
                }
            }
            covered += end - start;
        }
        double score = expected == 0 ? 0 : clamp(covered / (double) expected);
        if (score < config.readyThreshold()) {
            findings.add(finding("LOW_SOURCE_COVERAGE", severity(score),
                    "来源字符覆盖率不足", document.blocks()));
        }
        return score;
    }

    /** 统计页面内阅读序号回退，暴露多栏或版面排序错误。 */
    private double order(DocumentIr document, List<Finding> findings) {
        int comparisons = 0;
        int violations = 0;
        List<Block> affected = new ArrayList<>();
        for (DocumentIr.Page page : document.pages()) {
            for (int index = 1; index < page.blocks().size(); index++) {
                comparisons++;
                Block previous = page.blocks().get(index - 1);
                Block current = page.blocks().get(index);
                if (current.readingOrder() <= previous.readingOrder()) {
                    violations++;
                    affected.add(current);
                }
            }
        }
        double score = comparisons == 0 ? 1 : clamp(1 - violations / (double) comparisons);
        if (violations > 0) {
            findings.add(finding("READING_ORDER_CONFLICT", severity(score),
                    "页面阅读顺序存在重复或回退", affected));
        }
        return score;
    }

    /** 仅对 OCR 来源块聚合置信度，原生文本不稀释 OCR 风险。 */
    private double ocr(DocumentIr document, List<Finding> findings) {
        List<Block> blocks = document.blocks().stream()
                .filter(block -> block.flags().contains(Flag.OCR_TEXT)
                        || block.type() == BlockType.IMAGE_TEXT).toList();
        if (blocks.isEmpty()) return 1;
        double score = blocks.stream().mapToDouble(Block::confidence).average().orElse(0);
        if (score < config.readyThreshold()) {
            findings.add(finding("LOW_OCR_CONFIDENCE", severity(score),
                    "OCR文本平均置信度不足", blocks));
        }
        return score;
    }

    /** 以非空单元格比例评估表格结构是否可用于检索。 */
    private double table(DocumentIr document, List<Finding> findings) {
        List<Block> tables = document.blocks().stream()
                .filter(block -> block.type() == BlockType.TABLE || block.table() != null).toList();
        if (tables.isEmpty()) return 1;
        int total = 0;
        int usable = 0;
        List<Block> affected = new ArrayList<>();
        for (Block block : tables) {
            if (block.table() == null || block.table().cells().isEmpty()) {
                affected.add(block);
                total++;
                continue;
            }
            for (DocumentIr.TableCell cell : block.table().cells()) {
                total++;
                if (!cell.normalizedText().isBlank()) usable++;
            }
            if (block.table().cells().stream().anyMatch(cell -> cell.normalizedText().isBlank())) {
                affected.add(block);
            }
        }
        double score = total == 0 ? 0 : usable / (double) total;
        if (score < config.readyThreshold()) {
            findings.add(finding("TABLE_CELL_COVERAGE_LOW", severity(score),
                    "表格存在缺失或空白单元格", affected));
        }
        return score;
    }

    /** 用已标记重复块占比衡量清洗后的有效内容密度。 */
    private double duplicate(DocumentIr document, List<Finding> findings) {
        List<Block> textual = document.blocks().stream().filter(block -> !block.normalizedText().isBlank()).toList();
        List<Block> duplicates = textual.stream()
                .filter(block -> block.flags().contains(Flag.DUPLICATE_BLOCK)).toList();
        double score = textual.isEmpty() ? 0 : clamp(1 - duplicates.size() / (double) textual.size());
        if (!duplicates.isEmpty()) {
            findings.add(finding("DUPLICATE_CONTENT", severity(score),
                    "文档包含被抑制的重复内容块", duplicates));
        }
        return score;
    }

    /** 将替换字符按每百字符惩罚，快速识别解码损坏。 */
    private double replacement(DocumentIr document, List<Finding> findings) {
        long characters = document.blocks().stream().map(Block::normalizedText).mapToLong(String::length).sum();
        long replacements = document.blocks().stream().map(Block::normalizedText)
                .flatMapToInt(String::chars).filter(value -> value == '\uFFFD').count();
        double score = characters == 0 ? 0 : clamp(1 - replacements * 100.0 / characters);
        if (replacements > 0) {
            List<Block> affected = document.blocks().stream()
                    .filter(block -> block.normalizedText().indexOf('\uFFFD') >= 0).toList();
            findings.add(finding("REPLACEMENT_CHARACTER_FOUND", severity(score),
                    "解析文本包含Unicode替换字符", affected));
        }
        return score;
    }

    /** 将安全和语言风险写入报告，但不重复改变质量分。 */
    private void annotateRisks(DocumentIr document, List<Finding> findings) {
        List<Block> injection = flagged(document, Flag.PROMPT_INJECTION);
        if (!injection.isEmpty()) {
            findings.add(finding("PROMPT_INJECTION_MARKED", Severity.WARNING,
                    "内容包含提示注入特征，进入模型前需要隔离", injection));
        }
        List<Block> sensitive = flagged(document, Flag.SENSITIVE_CONTENT);
        if (!sensitive.isEmpty()) {
            findings.add(finding("SENSITIVE_CONTENT_MARKED", Severity.WARNING,
                    "内容包含敏感信息特征", sensitive));
        }
        List<Block> language = flagged(document, Flag.LANGUAGE_MISMATCH);
        if (!language.isEmpty()) {
            findings.add(finding("LANGUAGE_MISMATCH", Severity.WARNING,
                    "块语言与文档语言不一致", language));
        }
    }

    /** 按硬拒绝、人工复核、带警告可用、直接可用的顺序裁决。 */
    private DocumentQualityDisposition disposition(DocumentIr document, double coverage, double order,
                                                   double ocr, double table, double duplicate,
                                                   double replacement, double overall, List<Finding> findings) {
        if (document.blocks().isEmpty() || coverage < config.minimumCoverage()
                || overall < config.rejectedThreshold()) {
            return DocumentQualityDisposition.REJECTED;
        }
        double minimumComponent = Math.min(coverage, Math.min(order,
                Math.min(ocr, Math.min(table, Math.min(duplicate, replacement)))));
        if (overall < config.reviewThreshold() || minimumComponent < config.reviewComponentThreshold()) {
            return DocumentQualityDisposition.NEEDS_REVIEW;
        }
        if (overall < config.readyThreshold() || !findings.isEmpty() || !document.warnings().isEmpty()) {
            return DocumentQualityDisposition.READY_WITH_WARNING;
        }
        return DocumentQualityDisposition.READY;
    }

    /** 找出包含指定风险标记的内容块。 */
    private List<Block> flagged(DocumentIr document, Flag flag) {
        return document.blocks().stream().filter(block -> block.flags().contains(flag)).toList();
    }

    /** 将受影响块压缩为去重后的稳定标识列表。 */
    private Finding finding(String code, Severity severity, String message, List<Block> blocks) {
        return new Finding(code, severity, message, blocks.stream().map(Block::blockId).distinct().toList());
    }

    /** 单项分低于复核阈值时升级为错误。 */
    private Severity severity(double score) {
        return score < config.reviewComponentThreshold() ? Severity.ERROR : Severity.WARNING;
    }

    /** 防止浮点计算越出评分边界。 */
    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** 统一保留四位小数，保证报告可复算和可比较。 */
    private double round(double value) {
        return Math.round(clamp(value) * 10_000.0) / 10_000.0;
    }

    /**
     * 质量处置阈值。
     */
    public record Config(double readyThreshold,
                         double reviewThreshold,
                         double rejectedThreshold,
                         double minimumCoverage,
                         double reviewComponentThreshold) {
        public Config {
            validate(readyThreshold, "readyThreshold");
            validate(reviewThreshold, "reviewThreshold");
            validate(rejectedThreshold, "rejectedThreshold");
            validate(minimumCoverage, "minimumCoverage");
            validate(reviewComponentThreshold, "reviewComponentThreshold");
            if (!(readyThreshold > reviewThreshold && reviewThreshold > rejectedThreshold)) {
                throw new IllegalArgumentException("处置阈值必须按READY、REVIEW、REJECTED递减");
            }
        }

        /** 每个处置阈值都必须是有限概率值。 */
        private static void validate(double value, String field) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(field + "必须位于0到1之间");
            }
        }
    }
}
