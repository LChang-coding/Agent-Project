package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import cn.bugstack.ai.domain.tool.service.support.SkillPackageReader;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具副作用唯一出口：先授权和幂等领取，再路由 Skill/MCP，并闭环审计。 */
@Service
public class ToolGateway {

    /** 限制返回模型的正文，避免工具结果撑爆上下文。 */
    private static final int MAX_RESULT_LENGTH = 16_000;

    /** 读取已发布 Skill 包。 */
    private final ObjectStorageService objectStorageService;
    /** 执行标准 SSE/Stdio MCP 协议。 */
    private final McpProtocolClientSupport mcpProtocolClientSupport;
    /** 锁定运行并领取唯一外部执行权。 */
    private final ToolDispatchAuthorizationService dispatchAuthorizationService;
    /** 安全读取 Skill 包中的 SKILL.md。 */
    private final SkillPackageReader skillPackageReader;
    /** 只序列化工具输入、输出和 MCP 参数。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 兼容旧式直连 HTTP MCP；标准 SSE/Stdio 不使用它。 */
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 创建工具网关；参数是对象存储、MCP 客户端、分发授权和 Skill 包读取器；返回网关实例。
     */
    public ToolGateway(ObjectStorageService objectStorageService, McpProtocolClientSupport mcpProtocolClientSupport,
                       ToolDispatchAuthorizationService dispatchAuthorizationService,
                       SkillPackageReader skillPackageReader) {
        this.objectStorageService = objectStorageService;
        this.mcpProtocolClientSupport = mcpProtocolClientSupport;
        this.dispatchAuthorizationService = dispatchAuthorizationService;
        this.skillPackageReader = skillPackageReader;
    }

    /** 先领取执行权；只有 claimed=true 的请求才能产生外部副作用。 */
    public Map<String, Object> invoke(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context) {
        checkInvoke(tool, context);
        long begin = System.currentTimeMillis();
        String inputJson = toJson(input);
        long claimStarted = System.currentTimeMillis();
        AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                context.getTraceId(), "dispatch_authorize", "开始校验运行状态并领取幂等调用权",
                "started", 0L));
        ToolDispatchClaimEntity claim;
        try {
            claim = dispatchAuthorizationService.claim(tool, context, inputJson);
        } catch (RuntimeException exception) {
            AiLog.warn(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                    context.getTraceId(), "dispatch_authorize",
                    "运行状态校验或幂等调用权领取失败，未调用外部工具", "failed",
                    System.currentTimeMillis() - claimStarted)
                    .field("errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
        if (!claim.isClaimed()) {
            // 重试只重放持久化结果，started 未知态也绝不二次执行。
            AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                    context.getTraceId(), "dispatch_authorize", "命中既有工具调用，不重复产生外部消耗",
                    "replayed", System.currentTimeMillis() - claimStarted));
            return duplicateResult(claim.getCallLog());
        }
        AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                context.getTraceId(), "dispatch_authorize", "运行状态校验和幂等调用权领取完成",
                "completed", System.currentTimeMillis() - claimStarted));
        ToolCallLogEntity callLog = claim.getCallLog();
        AiLog.info(AiLog.tool().callStarted(context.getTenantId(), context.getUserId(), context.getSessionId(),
                context.getRunId(),
                tool.getToolType(), tool.getToolId(), tool.getToolName(), context.getTraceId()));
        try {
            AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                    context.getTraceId(), "runtime_route",
                    ToolType.SKILL.equals(tool.getToolType()) ? "路由到Skill运行时" : "路由到MCP运行时",
                    "completed", 0L));
            // 此处是通过授权和幂等门禁后唯一的真实工具执行点。
            String output = dispatch(tool, input);
            long costMs = System.currentTimeMillis() - begin;
            dispatchAuthorizationService.finish(callLog, toJson(Map.of("result", output)),
                    ToolStatus.SUCCESS, null, null, costMs);
            AiLog.info(AiLog.tool().callSuccess(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(),
                    tool.getToolType(), tool.getToolId(), tool.getToolName(), context.getTraceId(), costMs));
            return Map.of("success", true, "result", output);
        } catch (Exception e) {
            // 工具异常转换为模型结果，并尽力将 started 审计推进为 failed。
            long costMs = System.currentTimeMillis() - begin;
            finishFailedSafely(callLog, e, costMs);
            AiLog.error(AiLog.tool().callFailed(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(),
                    tool.getToolType(), tool.getToolId(), tool.getToolName(), context.getTraceId(), costMs, e));
            return Map.of("success", false, "error", safeMessage(e));
        }
    }

    /** success/failed 重放终态；started 返回未知，禁止猜测后再次执行。 */
    private Map<String, Object> duplicateResult(ToolCallLogEntity log) {
        if (ToolStatus.SUCCESS.equals(log.getStatus()) && log.getOutputJson() != null) {
            try {
                Map<String, Object> output = objectMapper.readValue(log.getOutputJson(), new TypeReference<>() {
                });
                return Map.of("success", true, "result", String.valueOf(output.getOrDefault("result", "")),
                        "replayed", true);
            } catch (Exception ignored) {
                return Map.of("success", false, "error", "工具调用已完成，但历史结果无法解析");
            }
        }
        if (ToolStatus.FAILED.equals(log.getStatus())) {
            return Map.of("success", false, "error", defaultString(log.getErrorMessage(), "工具调用此前已失败"),
                    "replayed", true);
        }
        return Map.of("success", false, "error", "工具调用已开始，当前结果未知；为避免重复消耗不再执行",
                "replayed", true);
    }

    /** 审计更新失败只记录二次错误，不覆盖原始工具异常。 */
    private void finishFailedSafely(ToolCallLogEntity log, Exception error, long costMs) {
        try {
            dispatchAuthorizationService.finish(log, null, ToolStatus.FAILED,
                    error.getClass().getSimpleName(), safeMessage(error), costMs);
        } catch (Exception auditError) {
            AiLog.error(AiLog.tool().callFailed(log.getTenantId(), log.getUserId(), log.getSessionId(),
                    log.getRunId(),
                    log.getToolType(), log.getToolId(), log.getToolName(), log.getTraceId(), costMs, auditError));
        }
    }

    /**
     * 分发具体工具；参数是工具目录和入参；返回工具执行结果文本。
     */
    private String dispatch(ToolCatalogEntity tool, Map<String, Object> input) {
        if (ToolType.SKILL.equals(tool.getToolType())) {
            return invokeSkill(tool, input);
        }
        if (ToolType.MCP.equals(tool.getToolType())) {
            return invokeMcp(tool, input);
        }
        throw new AppException("TOOL_TYPE_UNSUPPORTED", "工具类型不支持：" + tool.getToolType());
    }

    /** Skill 调用读取已发布包并返回指令文本，不执行包内任意代码。 */
    private String invokeSkill(ToolCatalogEntity tool, Map<String, Object> input) {
        byte[] bytes = objectStorageService.getObject(tool.getBucket(), tool.getObjectKey());
        String skillMd = skillPackageReader.readSkillMd(bytes);
        String task = stringValue(input.get("task"));
        StringBuilder result = new StringBuilder();
        result.append("Skill 名称：").append(tool.getToolName()).append('\n');
        result.append("Skill 版本：").append(tool.getVersion()).append('\n');
        if (task != null && !task.isBlank()) {
            result.append("本次任务：").append(task).append("\n\n");
        }
        result.append(skillMd);
        return truncate(result.toString());
    }

    /** SSE/Stdio 走标准 MCP；http 保留旧式 JSON POST 兼容路径。 */
    private String invokeMcp(ToolCatalogEntity tool, Map<String, Object> input) {
        String transportType = tool.getTransportType() == null ? "" : tool.getTransportType().toLowerCase();
        if (!"http".equals(transportType) && !"sse".equals(transportType) && !"stdio".equals(transportType)) {
            throw new AppException("TOOL_MCP_LOCAL_DISABLED", "local MCP 当前未接入 ToolGateway");
        }
        if (!"stdio".equals(transportType) && (tool.getEndpoint() == null || tool.getEndpoint().isBlank())) {
            throw new AppException("TOOL_MCP_ENDPOINT_EMPTY", "MCP endpoint 不能为空");
        }
        if ("sse".equals(transportType) || "stdio".equals(transportType)) {
            // 标准协议调用前解析并校验远程具体工具名。
            McpCallCommand command = parseMcpCallCommand(tool, input);
            return mcpProtocolClientSupport.callTool(tool, command.toolName(), command.arguments());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tool.getEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(input), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new AppException("TOOL_MCP_CALL_FAILED", "MCP 返回状态码：" + response.statusCode());
            }
            return truncate(response.body());
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("TOOL_MCP_CALL_FAILED", "MCP 调用失败：" + e.getMessage(), e);
        }
    }

    /** 接受兼容工具名字段；仅单工具 schema 可省略 toolName。 */
    private McpCallCommand parseMcpCallCommand(ToolCatalogEntity tool, Map<String, Object> input) {
        Map<String, Object> arguments = parseMcpArguments(input);
        String toolName = firstText(input.get("toolName"), input.get("mcpToolName"), input.get("name"),
                arguments.remove("toolName"), arguments.remove("mcpToolName"), arguments.remove("_toolName"));
        if (toolName == null || toolName.isBlank()) {
            toolName = inferSingleToolName(tool.getSchemaJson());
        }
        if (toolName == null || toolName.isBlank()) {
            List<String> toolNames = mcpProtocolClientSupport.toolNames(tool.getSchemaJson());
            throw new AppException("TOOL_MCP_TOOL_NAME_EMPTY", "MCP 调用缺少 toolName，可用工具：" + String.join(",", toolNames));
        }
        return new McpCallCommand(toolName, arguments);
    }

    /** argumentsJson 可为对象或 JSON 字符串；缺失时使用去除路由字段后的剩余参数。 */
    private Map<String, Object> parseMcpArguments(Map<String, Object> input) {
        Object argumentsJson = input.get("argumentsJson");
        if (argumentsJson instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (argumentsJson != null && !String.valueOf(argumentsJson).isBlank()) {
            try {
                return objectMapper.readValue(String.valueOf(argumentsJson), new TypeReference<>() {
                });
            } catch (Exception e) {
                throw new AppException("TOOL_MCP_ARGUMENTS_INVALID", "argumentsJson 必须是合法 JSON 对象");
            }
        }
        Map<String, Object> arguments = new LinkedHashMap<>(input);
        arguments.remove("toolName");
        arguments.remove("mcpToolName");
        arguments.remove("name");
        return arguments;
    }

    /**
     * 规范化 Map；参数是原始 Map；返回字符串 Key 的 Map。
     */
    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * 推断单工具 MCP 的工具名；参数是工具 Schema；返回工具名。
     */
    private String inferSingleToolName(String schemaJson) {
        List<String> names = mcpProtocolClientSupport.toolNames(schemaJson);
        return names.size() == 1 ? names.get(0) : null;
    }

    /**
     * 读取第一个非空文本；参数是候选值；返回文本。
     */
    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = stringValue(value);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /** 工具标识或可信租户/用户缺失时，在领取执行权前失败关闭。 */
    private void checkInvoke(ToolCatalogEntity tool, ToolInvokeContextEntity context) {
        if (tool == null || tool.getToolId() == null || tool.getToolId().isBlank()) {
            throw new AppException("TOOL_NOT_FOUND", "工具不存在");
        }
        if (context == null || blank(context.getTenantId()) || blank(context.getUserId())) {
            throw new AppException("TOOL_CONTEXT_INVALID", "工具调用身份不完整");
        }
    }

    /** 审计序列化失败退化为空对象，不影响门禁本身。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<>() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 读取字符串值；参数是对象；返回字符串。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 判断字符串是否为空；参数是字符串；返回是否为空。
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 默认字符串；参数是候选值和默认值；返回非空值。
     */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 安全异常信息；参数是异常；返回可记录的错误信息。
     */
    private String safeMessage(Exception e) {
        if (e instanceof AppException appException && appException.getInfo() != null && !appException.getInfo().isBlank()) {
            return truncate(appException.getInfo());
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : truncate(message);
    }

    /**
     * 截断文本；参数是原文；返回限制长度后的文本。
     */
    private String truncate(String value) {
        if (value == null || value.length() <= MAX_RESULT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESULT_LENGTH);
    }

    /**
     * MCP 调用命令。
     */
    private record McpCallCommand(String toolName, Map<String, Object> arguments) {
    }
}
