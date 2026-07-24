package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 会话级RAG选择模式。
 */
public enum SessionRagMode {
    /** 不为后续运行启用RAG。 */
    OFF,
    /** 自动使用当前运行目标的全部可用绑定。 */
    AUTO,
    /** 仅使用用户为会话显式选择的绑定。 */
    MANUAL;

    /**
     * 按持久化值解析模式。
     *
     * @param value 持久化模式
     * @param ragEnabled 旧版开关值
     * @return 兼容旧会话的模式
     */
    public static SessionRagMode resolve(String value, Boolean ragEnabled) {
        if (value != null && !value.isBlank()) {
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 旧数据或人工写入异常时按兼容开关降级，避免查询接口整体失败。
            }
        }
        return Boolean.TRUE.equals(ragEnabled) ? AUTO : OFF;
    }

    /**
     * 返回兼容旧运行链路的开关值。
     *
     * @return 是否启用RAG
     */
    public boolean enabled() {
        return this != OFF;
    }
}
