package cn.bugstack.ai.domain.tool.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台工具的一次执行结果。
 *
 * @param success 是否成功完成工具的业务操作
 * @param modelResult 可返回给模型的受控字段，不应包含内部身份或敏感配置
 * @param auditResult 仅供服务端持久化审计的扩展字段
 * @param error 失败时可安全返回的错误说明
 */
public record PlatformToolResult(boolean success, Map<String, Object> modelResult, Map<String, Object> auditResult,
                                  String error) {

    /** 将模型结果和审计结果复制为不可变映射，避免调用结束后被外部修改。 */
    public PlatformToolResult {
        modelResult = modelResult == null ? Map.of() : Map.copyOf(modelResult);
        auditResult = auditResult == null ? Map.of() : Map.copyOf(auditResult);
    }

    /**
     * 创建成功结果。
     *
     * @param result 允许模型读取的工具执行结果
     * @return 不包含额外审计字段的成功结果
     */
    public static PlatformToolResult success(Map<String, Object> result) {
        return new PlatformToolResult(true, result, Map.of(), null);
    }

    /**
     * 创建失败结果。
     *
     * @param error 可安全返回给模型的错误说明
     * @return 不包含业务数据的失败结果
     */
    public static PlatformToolResult failure(String error) {
        return new PlatformToolResult(false, Map.of(), Map.of(), error);
    }

    /**
     * 生成统一的模型响应结构。
     *
     * @return 成功时包含业务字段、失败时只包含错误说明的响应
     */
    public Map<String, Object> response() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        if (success) result.putAll(modelResult);
        else result.put("error", error == null ? "平台工具调用失败" : error);
        return result;
    }
}
