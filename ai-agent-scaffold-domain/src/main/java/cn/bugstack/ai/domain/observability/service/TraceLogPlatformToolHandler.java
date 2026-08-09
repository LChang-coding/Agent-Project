package cn.bugstack.ai.domain.observability.service;

import cn.bugstack.ai.domain.observability.adapter.port.TraceLogQueryPort;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolHandler;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 允许 Agent 查看当前运行日志的平台工具。
 *
 * <p>模型只能调整查询时间和返回条数，不能提交任意 LogQL。即使模型传入 Trace ID，也必须与服务端
 * 保存的当前运行 Trace ID 完全一致，防止通过猜测编号读取其他运行或其他租户的日志。</p>
 */
@Service
public class TraceLogPlatformToolHandler implements PlatformToolHandler {

    private static final String FUNCTION_NAME = "query_trace_logs";
    private static final int DEFAULT_LOOKBACK_MINUTES = 30;
    private static final int MAX_LOOKBACK_MINUTES = 120;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> MODEL_ARGUMENTS = Set.of("traceId", "lookbackMinutes", "limit");

    /** 真正访问日志系统的只读端口。 */
    private final TraceLogQueryPort queryPort;

    /** 创建处理器并登记固定的模型函数名。 */
    public TraceLogPlatformToolHandler(PlatformToolRegistry registry, TraceLogQueryPort queryPort) {
        this.queryPort = queryPort;
        registry.register(FUNCTION_NAME, this);
    }

    /**
     * 校验当前运行范围后读取日志，并只把时间和脱敏正文交给模型。
     */
    @Override
    public PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input,
                                     ToolInvokeContextEntity context) {
        requireContext(context);
        // 只接受公开给模型的三个参数，避免新增字段被误当成查询条件透传。
        Map<String, Object> arguments = input == null ? Map.of() : input;
        if (!MODEL_ARGUMENTS.containsAll(arguments.keySet())) {
            throw new AppException("TRACE_LOG_ARGUMENT_INVALID", "日志工具包含不支持的参数");
        }

        String requestedTraceId = stringValue(arguments.get("traceId"));
        // 查询范围由当前运行决定，模型不能通过填写其他 Trace ID 横向读取日志。
        if (requestedTraceId != null && !requestedTraceId.equals(context.getTraceId())) {
            throw new AppException("TRACE_LOG_SCOPE_MISMATCH", "只能查询当前这次运行的日志");
        }
        // 时间和条数即使由模型填写，也会被收紧到服务端允许的范围。
        int lookbackMinutes = boundedInteger(arguments.get("lookbackMinutes"),
                DEFAULT_LOOKBACK_MINUTES, 1, MAX_LOOKBACK_MINUTES);
        int limit = boundedInteger(arguments.get("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT);

        TraceLogQueryPort.QueryResult queried = queryPort.query(new TraceLogQueryPort.QueryCommand(
                context.getTenantId(), context.getTraceId(), lookbackMinutes, limit));
        List<Map<String, Object>> entries = queried.entries().stream().map(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", entry.timestamp() == null ? null : entry.timestamp().toString());
            item.put("line", entry.line());
            return item;
        }).toList();

        Map<String, Object> modelResult = new LinkedHashMap<>();
        modelResult.put("traceId", context.getTraceId());
        modelResult.put("lookbackMinutes", lookbackMinutes);
        modelResult.put("returnedLines", entries.size());
        modelResult.put("truncated", queried.truncated());
        modelResult.put("entries", entries);
        Map<String, Object> auditResult = Map.of("lookbackMinutes", lookbackMinutes,
                "requestedLimit", limit, "returnedLines", entries.size(), "truncated", queried.truncated());
        return new PlatformToolResult(true, modelResult, auditResult, null);
    }

    /** 缺少服务端运行身份时直接拒绝，避免把调用退化成公共日志搜索。 */
    private void requireContext(ToolInvokeContextEntity context) {
        if (context == null || blank(context.getTenantId()) || blank(context.getRunId())
                || blank(context.getTraceId())) {
            throw new AppException("TRACE_LOG_CONTEXT_INVALID", "日志工具缺少当前运行信息");
        }
    }

    /** 读取整数参数；超出服务端范围时收紧到边界，不能扩大日志读取量。 */
    private int boundedInteger(Object value, int defaultValue, int minimum, int maximum) {
        if (value == null) return defaultValue;
        final int parsed;
        try {
            parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new AppException("TRACE_LOG_ARGUMENT_INVALID", "日志工具参数必须是整数");
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
