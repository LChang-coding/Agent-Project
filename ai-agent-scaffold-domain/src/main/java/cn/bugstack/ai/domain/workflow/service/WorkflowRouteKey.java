package cn.bugstack.ai.domain.workflow.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 智能工作流路由键的唯一标准化与安全校验入口。 */
public final class WorkflowRouteKey {

    /** 路由键允许包含的最大 Unicode 码点数量。 */
    private static final int MAX_CODE_POINTS = 64;
    /** 兼容旧协议时用于识别独立路由控制行的格式。 */
    private static final Pattern MARKER_LINE = Pattern.compile("^\\s*\\[route:([^]\\r\\n]{1,128})]\\s*$");

    /** 常量和静态方法类不允许实例化。 */
    private WorkflowRouteKey() {
    }

    /**
     * 对路由键执行 NFKC、首尾空白清理和英文大小写折叠。
     *
     * @param value 原始主路由键或别名
     * @return 可用于精确比较的标准化文本；空值返回空字符串
     */
    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验路由键非空、长度受限且不包含控制字符或方括号。
     *
     * @param value 待校验的路由键
     * @return 是否可以保存并用于工具枚举或旧协议控制行
     */
    public static boolean valid(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) return false;
        return normalized.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == '[' || codePoint == ']');
    }

    /**
     * 使用统一标准化规则精确比较两个路由键。
     *
     * @param left 已配置的主路由键或别名
     * @param right 待匹配的模型选择
     * @return 左侧非空且两侧标准化结果相同时返回 true
     */
    public static boolean same(String left, String right) {
        String normalizedLeft = normalize(left);
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalize(right));
    }

    /**
     * 从回答末尾的独立控制行读取旧协议路由键，不解析正文中的相似文本。
     *
     * @param output 节点模型完整输出
     * @return 合法且已标准化的路由键；末尾没有合法控制行时返回空
     */
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
