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

/** 工具产生外部副作用前统一检查取消状态和上下文版本。 */
@Slf4j
@Service("toolExecutionGuardPlugin")
public class ToolExecutionGuardPlugin extends BasePlugin {

    /** 读取权威运行状态，并在必要时完成调用前压缩。 */
    private final RunExecutionGate runExecutionGate;

    /** 固定插件名，Runner 依此强制附加并去重。 */
    public ToolExecutionGuardPlugin(RunExecutionGate runExecutionGate) {
        super("toolExecutionGuardPlugin");
        this.runExecutionGate = runExecutionGate;
    }

    /** 返回空允许执行；返回结构化结果会短路真实工具调用。 */
    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs,
                                                          ToolContext toolContext) {
        if (toolContext == null || toolContext.state() == null) {
            // 无 ADK 运行上下文的非会话工具不在本插件治理范围内。
            return Maybe.empty();
        }
        Map<String, Object> state = toolContext.state();
        String runId = stringValue(state.get(ToolRuntimeContextKeys.RUN_ID));
        if (blank(runId)) {
            // 兼容启动期或独立测试调用；会话工具必须由 ChatService 注入 runId。
            return Maybe.empty();
        }
        // 只用可信 state 构造门禁上下文，不读取模型生成的 toolArgs。
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
            // 调用前压缩改变了上下文版本：阻止本次工具，要求模型基于新上下文重新推理。
            ChatRunEntity currentRun = runExecutionGate.currentRun(context);
            state.put(ToolRuntimeContextKeys.CONTEXT_REVISION, currentRun.getCurrentContextRevision());
            return Maybe.just(blockedResult("RUN_CONTEXT_REFRESH_REQUIRED",
                    "上下文已安全压缩，请基于最新上下文重新推理后再决定是否调用工具", true));
        } catch (AppException e) {
            // 取消、版本冲突和身份错误都转成模型可理解的阻断结果，不执行工具。
            log.info("工具调用被运行闸门拦截 runId:{} tool:{} code:{}",
                    runId, tool == null ? null : tool.name(), e.getCode());
            return Maybe.just(blockedResult(e.getCode(), safeMessage(e), false));
        } catch (Exception e) {
            // 未知门禁故障失败关闭，宁可拒绝也不冒险产生外部副作用。
            log.error("工具调用前守卫异常，已失败关闭 runId:{} tool:{}",
                    runId, tool == null ? null : tool.name(), e);
            return Maybe.just(blockedResult("TOOL_GATE_FAILED", "工具调用前安全检查失败，已拒绝执行", false));
        }
    }

    /** 构造 GatewayToolset 可透传给模型的统一阻断协议。 */
    private Map<String, Object> blockedResult(String code, String message, boolean retryRequired) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("blocked", true);
        result.put("retryRequired", retryRequired);
        result.put("code", defaultString(code, "TOOL_EXECUTION_BLOCKED"));
        result.put("error", defaultString(message, "工具调用已被拦截"));
        return result;
    }

    /** 提取可展示错误；不为空时保留领域错误原因。 */
    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "工具调用已被拦截" : e.getMessage();
    }

    /** 将可选状态值规范为字符串。 */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 解析上下文 revision；非法值交由门禁按缺失处理。 */
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

    /** 解析上下文可见序号；非法值交由门禁按缺失处理。 */
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

    /** 空主值回退到 ADK 提供的可信用户或会话值。 */
    private String defaultString(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    /** 统一 null 与空白判断。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
