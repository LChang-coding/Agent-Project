package cn.bugstack.ai.domain.agent.service.armory.matter.model;

import com.google.genai.types.GenerateContentResponseUsageMetadata;

public final class ModelObservabilityContext {

    private static final ThreadLocal<Snapshot> CONTEXT = new ThreadLocal<>();

    private ModelObservabilityContext() {
    }

    public static void set(String modelVersion, GenerateContentResponseUsageMetadata usageMetadata) {
        CONTEXT.set(new Snapshot(modelVersion, usageMetadata));
    }

    public static Snapshot get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record Snapshot(String modelVersion, GenerateContentResponseUsageMetadata usageMetadata) {
    }
}
