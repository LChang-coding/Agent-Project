package cn.bugstack.ai.domain.workflow.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 智能工作流路由键的唯一标准化与安全校验入口。 */
public final class WorkflowRouteKey {

    private static final int MAX_CODE_POINTS = 64;
    private static final Pattern MARKER_LINE = Pattern.compile("^\\s*\\[route:([^]\\r\\n]{1,128})]\\s*$");

    private WorkflowRouteKey() {
    }

    /** NFKC、去首尾空白并折叠英文大小写；中文等 Unicode 键保持原义。 */
    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    /** 仅允许可放进单行 route marker 的非空安全文本。 */
    public static boolean valid(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) return false;
        return normalized.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == '[' || codePoint == ']');
    }

    /** 主键和别名均使用标准化后的精确等值匹配。 */
    public static boolean same(String left, String right) {
        String normalizedLeft = normalize(left);
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalize(right));
    }

    /** 只读取回答末尾的独立控制行，不从自然语言正文猜测路由。 */
    public static String markerAtEnd(String output) {
        if (output == null || output.isBlank()) return null;
        String[] lines = output.split("\\R", -1);
        for (int index = lines.length - 1; index >= 0; index--) {
            if (lines[index].isBlank()) continue;
            Matcher matcher = MARKER_LINE.matcher(lines[index]);
            if (!matcher.matches() || !valid(matcher.group(1))) return null;
            return normalize(matcher.group(1));
        }
        return null;
    }
}
