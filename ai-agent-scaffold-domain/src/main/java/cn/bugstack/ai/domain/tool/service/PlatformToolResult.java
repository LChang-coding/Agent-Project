package cn.bugstack.ai.domain.tool.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record PlatformToolResult(boolean success, Map<String, Object> modelResult, Map<String, Object> auditResult,
                                  String error) {
    public PlatformToolResult {
        modelResult = modelResult == null ? Map.of() : Map.copyOf(modelResult);
        auditResult = auditResult == null ? Map.of() : Map.copyOf(auditResult);
    }

    public static PlatformToolResult success(Map<String, Object> result) {
        return new PlatformToolResult(true, result, Map.of(), null);
    }

    public static PlatformToolResult failure(String error) {
        return new PlatformToolResult(false, Map.of(), Map.of(), error);
    }

    public Map<String, Object> response() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        if (success) result.putAll(modelResult);
        else result.put("error", error == null ? "平台工具调用失败" : error);
        return result;
    }
}
