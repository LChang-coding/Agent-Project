package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextAssemblyResult;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ADK 上下文注入插件。
 * <p>在每次模型调用前读取业务会话切面，并追加为系统指令。</p>
 */
@Slf4j
@Service("contextInjectionPlugin")
public class ContextInjectionPlugin extends BasePlugin {

    private final ConversationMemoryService conversationMemoryService;

    /**
     * 创建上下文注入插件；参数是会话记忆服务；返回插件实例。
     */
    public ContextInjectionPlugin(ConversationMemoryService conversationMemoryService) {
        super("contextInjectionPlugin");
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 模型调用前注入上下文；参数是回调上下文和请求构造器；返回空表示继续调用模型。
     */
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        try {
            Map<String, Object> state = callbackContext.state();
            ContextAssemblyResult result = conversationMemoryService.assemble(ContextAssembleRequest.builder()
                    .tenantId(stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID)))
                    .userId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), callbackContext.userId()))
                    .sessionId(stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID)))
                    .visibleThroughSequence(integerValue(state.get(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE)))
                    .attachmentVisibleThroughSequence(integerValue(
                            state.get(ToolRuntimeContextKeys.CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE)))
                    .upstreamOutput(stringValue(state.get(ToolRuntimeContextKeys.CONTEXT_UPSTREAM_OUTPUT)))
                    .traceId(stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID)))
                    .build());
            if (result.getInstruction() != null && !result.getInstruction().isBlank()) {
                llmRequest.appendInstructions(List.of(result.getInstruction()));
            }
            return Maybe.empty();
        } catch (Exception e) {
            log.warn("上下文注入失败 traceId:{} invocationId:{} sessionId:{}",
                    extractTraceId(callbackContext), callbackContext.invocationId(), callbackContext.sessionId(), e);
            return Maybe.empty();
        }
    }

    private String extractTraceId(CallbackContext callbackContext) {
        if (callbackContext == null || callbackContext.state() == null) {
            return TraceContext.getTraceId();
        }
        return defaultString(stringValue(callbackContext.state().get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.getTraceId());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
