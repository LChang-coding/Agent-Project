package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import java.util.Locale;

/** 对外统一的思考强度；供应商私有字段由各自适配器解释。 */
public enum ReasoningMode {
    DISABLED,
    SUMMARY,
    MEDIUM;

    public static ReasoningMode resolve(String value) {
        if (value == null || value.isBlank()) return MEDIUM;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MEDIUM;
        }
    }
}
