package cn.bugstack.ai.types.observability;

import java.util.Map;

public final class Logfmt {

    private static final String SIMPLE_VALUE_PATTERN = "[A-Za-z0-9_./:@+-]+";

    private Logfmt() {
    }

    public static String format(Map<String, ?> fields) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(entry.getKey()).append('=').append(formatValue(entry.getValue()));
        }
        return builder.toString();
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        if (text.matches(SIMPLE_VALUE_PATTERN)) {
            return text;
        }
        return "\"" + escape(text) + "\"";
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
