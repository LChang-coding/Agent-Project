package cn.bugstack.ai.domain.agent.service.armory.matter.model;

import com.google.genai.types.GenerateContentResponseUsageMetadata;

/** 在同一模型回调线程内桥接 Spring AI 元数据与 ADK 日志插件。 */
public final class ModelObservabilityContext {

    /** 单次回调临时值；每个终态和异常路径都必须清理。 */
    private static final ThreadLocal<Snapshot> CONTEXT = new ThreadLocal<>();

    /** 禁止实例化纯静态上下文。 */
    private ModelObservabilityContext() {
    }

    /** 覆盖当前线程最近一次供应商模型与 Token 快照。 */
    public static void set(String modelVersion, GenerateContentResponseUsageMetadata usageMetadata) {
        CONTEXT.set(new Snapshot(modelVersion, usageMetadata));
    }

    /** 返回当前线程快照；没有观测结果时为 null。 */
    public static Snapshot get() {
        return CONTEXT.get();
    }

    /** 删除 ThreadLocal，防止线程池复用串用上次调用数据。 */
    public static void clear() {
        CONTEXT.remove();
    }

    /** 同时保存模型版本和标准化后的 ADK 用量元数据。 */
    public record Snapshot(String modelVersion, GenerateContentResponseUsageMetadata usageMetadata) {
    }
}
