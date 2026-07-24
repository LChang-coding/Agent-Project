package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextAssemblyResult;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.service.RagInvocationEvidenceStore;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
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
    private final RagInvocationEvidenceStore evidenceStore;

    /**
     * 创建上下文注入插件；参数是会话记忆服务；返回插件实例。
     */
    public ContextInjectionPlugin(ConversationMemoryService conversationMemoryService,
                                  RagInvocationEvidenceStore evidenceStore) {
        super("contextInjectionPlugin");
        this.conversationMemoryService = conversationMemoryService;
        this.evidenceStore = evidenceStore;
    }

    /**
     * 模型调用前注入上下文；参数是回调上下文和请求构造器；返回空表示继续调用模型。
     */
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> state = callbackContext.state();
        String tenantId = stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID));
        String userId = defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), callbackContext.userId());
        String sessionId = stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID));
        String runId = stringValue(state.get(ToolRuntimeContextKeys.RUN_ID));
        String traceId = stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID));
        boolean ragEnabled = state.get(ToolRuntimeContextKeys.RAG_TARGET_TYPE) != null;
        AiLog.info(AiLog.chat().contextStarted(tenantId, userId, sessionId, runId, ragEnabled)
                .field(AiLogFields.TRACE_ID, traceId));
        try {
            ContextAssemblyResult result = conversationMemoryService.assemble(ContextAssembleRequest.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .sessionId(sessionId)
                    .visibleThroughSequence(integerValue(state.get(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE)))
                    .attachmentVisibleThroughSequence(integerValue(
                            state.get(ToolRuntimeContextKeys.CONTEXT_ATTACHMENT_VISIBLE_THROUGH_SEQUENCE)))
                    .upstreamOutput(stringValue(state.get(ToolRuntimeContextKeys.CONTEXT_UPSTREAM_OUTPUT)))
                    .traceId(traceId)
                    .ragTargetType(enumValue(state.get(ToolRuntimeContextKeys.RAG_TARGET_TYPE)))
                    .ragTargetId(stringValue(state.get(ToolRuntimeContextKeys.RAG_TARGET_ID)))
                    .ragBindingIds(stringList(state.get(ToolRuntimeContextKeys.RAG_BINDING_IDS)))
                    .ragQuery(stringValue(state.get(ToolRuntimeContextKeys.RAG_QUERY)))
                    .runId(runId)
                    .build());
            if (result.getInstruction() != null && !result.getInstruction().isBlank()) {
                llmRequest.appendInstructions(List.of(result.getInstruction()));
            }
            if (result.getRagEvidence() != null && !result.getRagEvidence().isEmpty()) {
                evidenceStore.record(tenantId, userId, sessionId, runId,
                        defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_EVIDENCE_INVOCATION_ID)),
                                callbackContext.invocationId()),
                        result.getRagEvidence());
            }
            AiLog.info(AiLog.chat().contextCompleted(tenantId, userId, sessionId, runId, ragEnabled,
                    result.getEstimatedTokenCount(),
                    result.getRagEvidence() == null ? 0 : result.getRagEvidence().size(),
                    System.currentTimeMillis() - startedAt).field(AiLogFields.TRACE_ID, traceId));
            return Maybe.empty();
        } catch (Exception e) {
            AiLog.error(AiLog.chat().contextFailed(tenantId, userId, sessionId, runId, ragEnabled,
                    System.currentTimeMillis() - startedAt, e).field(AiLogFields.TRACE_ID, traceId));
            if (mustFailClosed(e)) {
                throw (RuntimeException) e;
            }
            log.warn("上下文注入失败 traceId:{} invocationId:{} sessionId:{}",
                    extractTraceId(callbackContext), callbackContext.invocationId(), callbackContext.sessionId(), e);
            return Maybe.empty();
        }
    }

    private boolean mustFailClosed(Exception exception) {
        if (!(exception instanceof AppException appException) || appException.getCode() == null) {
            return false;
        }
        return appException.getCode().startsWith("RAG_REQUIRED_")
                || appException.getCode().contains("SCOPE_VIOLATION");
    }

    private RagBindingTargetType enumValue(Object value) {
        if (value == null) return null;
        try {
            return RagBindingTargetType.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(item -> item != null)
                .map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
