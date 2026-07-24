package cn.bugstack.ai.domain.rag.model.document;

import java.util.List;
import java.util.Objects;

/**
 * 文档解析质量报告。
 * <p>所有分数取值为0到1，数值越高表示对应质量越好。</p>
 */
public record DocumentParseQualityReport(double coverage,
                                         double order,
                                         double ocr,
                                         double table,
                                         double duplicate,
                                         double replacement,
                                         double overall,
                                         DocumentQualityDisposition disposition,
                                         List<Finding> findings) {

    public DocumentParseQualityReport {
        coverage = score(coverage, "coverage");
        order = score(order, "order");
        ocr = score(ocr, "ocr");
        table = score(table, "table");
        duplicate = score(duplicate, "duplicate");
        replacement = score(replacement, "replacement");
        overall = score(overall, "overall");
        disposition = Objects.requireNonNull(disposition, "disposition不能为空");
        findings = List.copyOf(Objects.requireNonNullElse(findings, List.of()));
    }

    /**
     * 质量问题及其影响范围。
     */
    public record Finding(String code, Severity severity, String message, List<String> blockIds) {
        public Finding {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("质量问题编码不能为空");
            }
            severity = Objects.requireNonNull(severity, "severity不能为空");
            message = message == null ? "" : message;
            blockIds = List.copyOf(Objects.requireNonNullElse(blockIds, List.of()));
        }
    }

    /**
     * 质量问题严重度。
     */
    public enum Severity {
        INFO, WARNING, ERROR
    }

    private static double score(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + "必须位于0到1之间");
        }
        return value;
    }
}
