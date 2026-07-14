package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.ToolGateDecision;
import cn.bugstack.ai.domain.run.service.RunExecutionGate;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADK 工具执行守卫插件。
 * <p>在模型已决定调用工具、但外部副作用还未发生前，统一执行取消和上下文压缩检查。</p>
 */
@Slf4j
@Service("toolExecutionGuardPlugin")
public class ToolExecutionGuardPlugin extends BasePlugin {

    private final RunExecutionGate runExecutionGate;

    /**
     * 创建工具执行守卫；参数是运行执行闸门；返回插件实例。
     */
    public ToolExecutionGuardPlugin(RunExecutionGate runExecutionGate) {
        super("toolExecutionGuardPlugin");
        this.runExecutionGate = runExecutionGate;
    }

    /**
     * 工具调用前检查；参数是工具、入参和上下文；返回空表示允许，非空结果表示拦截。
     */
    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs,
                                                          ToolContext toolContext) {
        if (toolContext == null || toolContext.state() == null) {
            return Maybe.empty();
        }
        Map<String, Object> state = toolContext.state();
        String runId = stringValue(state.get(ToolRuntimeContextKeys.RUN_ID));
        if (blank(runId)) {
            return Maybe.empty();
        }
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId(stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID)))
                .userId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), toolContext.userId()))
                .sessionId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID)), toolContext.sessionId()))
                .workflowId(stringValue(state.get(ToolRuntimeContextKeys.WORKFLOW_ID)))
                .invocationId(toolContext.invocationId())
                .runId(runId)
                .contextRevision(longValue(state.get(ToolRuntimeContextKeys.CONTEXT_REVISION)))
                .functionCallId(toolContext.functionCallId().orElse(null))
                .traceId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.currentOrNewTraceId()))
                .build();
        try {
            ToolGateDecision decision = runExecutionGate.beforeTool(context,
                    integerValue(state.get(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE)));
            if (decision == ToolGateDecision.ALLOW) {
                return Maybe.empty();
            }
            ChatRunEntity currentRun = runExecutionGate.currentRun(context);
            state.put(ToolRuntimeContextKeys.CONTEXT_REVISION, currentRun.getCurrentContextRevision());
            return Maybe.just(blockedResult("RUN_CONTEXT_REFRESH_REQUIRED",
                    "上下文已安全压缩，请基于最新上下文重新推理后再决定是否调用工具", true));
        } catch (AppException e) {
            log.info("工具调用被运行闸门拦截 runId:{} tool:{} code:{}",
                    runId, tool == null ? null : tool.name(), e.getCode());
            return Maybe.just(blockedResult(e.getCode(), safeMessage(e), false));
        } catch (Exception e) {
            log.error("工具调用前守卫异常，已失败关闭 runId:{} tool:{}",
                    runId, tool == null ? null : tool.name(), e);
            return Maybe.just(blockedResult("TOOL_GATE_FAILED", "工具调用前安全检查失败，已拒绝执行", false));
        }
    }

    private Map<String, Object> blockedResult(String code, String message, boolean retryRequired) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("blocked", true);
        result.put("retryRequired", retryRequired);
        result.put("code", defaultString(code, "TOOL_EXECUTION_BLOCKED"));
        result.put("error", defaultString(message, "工具调用已被拦截"));
        return result;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "工具调用已被拦截" : e.getMessage();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String defaultString(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
