package cn.bugstack.ai.domain.rag.model.valobj;

import java.util.Locale;

/** RAG 的调用方式，与知识库绑定范围正交。 */
public enum RagInvocationMode {
    AUTO_CONTEXT,
    AGENT_TOOL;

    /** 历史空值和未知未来值保持自动上下文兼容行为。 */
    public static RagInvocationMode resolve(String value) {
        if (value == null || value.isBlank()) {
            return AUTO_CONTEXT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AUTO_CONTEXT;
        }
    }
}
