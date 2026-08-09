package cn.bugstack.ai.test.observability;

import cn.bugstack.ai.domain.observability.adapter.port.TraceLogQueryPort;
import cn.bugstack.ai.domain.observability.service.TraceLogPlatformToolHandler;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Trace 日志平台工具的参数、权限和结果边界测试。 */
public class TraceLogPlatformToolHandlerTest {

    @Test
    public void queriesOnlyCurrentTraceWithBoundedWindowAndLimit() {
        PlatformToolRegistry registry = new PlatformToolRegistry();
        AtomicReference<TraceLogQueryPort.QueryCommand> captured = new AtomicReference<>();
        TraceLogQueryPort port = command -> {
            captured.set(command);
            return new TraceLogQueryPort.QueryResult(List.of(
                    new TraceLogQueryPort.LogEntry(Instant.parse("2026-08-09T01:02:03Z"),
                            "event=workflow_node_completed success=true")), false);
        };
        new TraceLogPlatformToolHandler(registry, port);

        PlatformToolResult result = registry.dispatch(tool(), Map.of(
                "traceId", "trace-current", "lookbackMinutes", 999, "limit", 9999), context());

        Assert.assertTrue(result.success());
        Assert.assertEquals("trace-current", captured.get().traceId());
        Assert.assertEquals("tenant-a", captured.get().tenantId());
        Assert.assertEquals(120, captured.get().lookbackMinutes());
        Assert.assertEquals(500, captured.get().limit());
        Assert.assertEquals(1, result.modelResult().get("returnedLines"));
        Assert.assertEquals(1, ((List<?>) result.modelResult().get("entries")).size());
    }

    @Test
    public void rejectsAnotherTraceAndUnknownModelArguments() {
        PlatformToolRegistry registry = new PlatformToolRegistry();
        new TraceLogPlatformToolHandler(registry,
                command -> new TraceLogQueryPort.QueryResult(List.of(), false));

        assertCode("TRACE_LOG_SCOPE_MISMATCH", () -> registry.dispatch(tool(),
                Map.of("traceId", "trace-other"), context()));
        assertCode("TRACE_LOG_ARGUMENT_INVALID", () -> registry.dispatch(tool(),
                Map.of("traceId", "trace-current", "query", "{job=\"anything\"}"), context()));
    }

    private ToolInvokeContextEntity context() {
        return ToolInvokeContextEntity.builder().tenantId("tenant-a").userId("user-a")
                .runId("run-a").functionCallId("call-a").traceId("trace-current").build();
    }

    private ToolCatalogEntity tool() {
        return ToolCatalogEntity.builder().toolType(ToolType.PLATFORM).toolId("query_trace_logs")
                .toolCode("query_trace_logs").functionName("query_trace_logs").build();
    }

    private void assertCode(String code, Runnable action) {
        try {
            action.run();
            Assert.fail("预期平台工具拒绝越权或非法参数");
        } catch (AppException exception) {
            Assert.assertEquals(code, exception.getCode());
        }
    }
}
