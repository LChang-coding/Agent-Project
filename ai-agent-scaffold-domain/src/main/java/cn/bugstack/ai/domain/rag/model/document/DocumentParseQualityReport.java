package cn.bugstack.ai.domain.rag.model.document;

import java.util.List;
import java.util.Objects;

/**
 * 文档解析质量报告。
 * <p>所有分数取值为0到1，数值越高表示对应质量越好。</p>
 *
 * @param coverage 可用文本相对源文档的覆盖率
 * @param order 文本块阅读顺序正确度
 * @param ocr OCR 文本可用性分数
 * @param table 表格结构保留分数
 * @param duplicate 重复内容控制分数
 * @param replacement 异常替换字符控制分数
 * @param overall 按质量规则汇总的综合分数
 * @param disposition 该版本是否可激活的质量结论
 * @param findings 按质量规则产生的问题列表
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

    /** 校验所有分数范围，并将问题列表保存为防御副本。 */
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
     *
     * @param code 稳定的质量规则编码
     * @param severity 问题对版本激活的影响级别
     * @param message 可展示的问题说明
     * @param blockIds 受问题影响的文档块标识
     */
    public record Finding(String code, Severity severity, String message, List<String> blockIds) {
        /** 校验问题编码与级别，并将块标识列表保存为防御副本。 */
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
     * <p>INFO 仅留痕，WARNING 允许入库但需展示，ERROR 会阻止自动激活。</p>
     */
    public enum Severity {
        INFO, WARNING, ERROR
    }

    /** 校验并返回零到一的质量分数。 */
    private static double score(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + "必须位于0到1之间");
        }
        return value;
    }
}
