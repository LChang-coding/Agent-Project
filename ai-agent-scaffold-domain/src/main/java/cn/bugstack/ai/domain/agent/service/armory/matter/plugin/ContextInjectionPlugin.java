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

/** 每次模型调用前从数据库 Context Manager 组装历史、附件、上游输出与 RAG。 */
@Slf4j
@Service("contextInjectionPlugin")
public class ContextInjectionPlugin extends BasePlugin {

    /** 按运行快照和可见序号组装上下文。 */
    private final ConversationMemoryService conversationMemoryService;
    /** 暂存本次模型实际注入的 RAG 证据，供最终回答引用校验。 */
    private final RagInvocationEvidenceStore evidenceStore;

    /** 固定插件名，Runner 依此去重自动附加。 */
    public ContextInjectionPlugin(ConversationMemoryService conversationMemoryService,
                                  RagInvocationEvidenceStore evidenceStore) {
        super("contextInjectionPlugin");
        this.conversationMemoryService = conversationMemoryService;
        this.evidenceStore = evidenceStore;
    }

    /** 在模型调用前追加系统指令；返回空表示不短路模型。 */
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        long startedAt = System.currentTimeMillis();
        // 所有身份和切面均来自 ChatService 注入的可信 state。
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
            // Context Manager 统一决定历史、压缩摘要、附件、上游输出和 RAG 的预算。
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
                // 作为系统指令追加，不与原始用户 Content 混写。
                llmRequest.appendInstructions(List.of(result.getInstruction()));
            }
            if (result.getRagEvidence() != null && !result.getRagEvidence().isEmpty()) {
                // 工作流节点使用显式 evidenceInvocationId，普通 Agent 使用 ADK invocationId。
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
                // 必需知识库不可用或检索越权时禁止无 RAG 继续回答。
                throw (RuntimeException) e;
            }
            log.warn("上下文注入失败 traceId:{} invocationId:{} sessionId:{}",
                    extractTraceId(callbackContext), callbackContext.invocationId(), callbackContext.sessionId(), e);
            return Maybe.empty();
        }
    }

    /** 仅必需 RAG 和范围违规失败关闭；普通上下文故障允许模型无注入继续。 */
    private boolean mustFailClosed(Exception exception) {
        if (!(exception instanceof AppException appException) || appException.getCode() == null) {
            return false;
        }
        return appException.getCode().startsWith("RAG_REQUIRED_")
                || appException.getCode().contains("SCOPE_VIOLATION");
    }

    /** 将 state 中类型值安全解析为受支持的绑定类型。 */
    private RagBindingTargetType enumValue(Object value) {
        if (value == null) return null;
        try {
            return RagBindingTargetType.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 优先取调用 state 中 traceId，缺失时回退当前线程链路。 */
    private String extractTraceId(CallbackContext callbackContext) {
        if (callbackContext == null || callbackContext.state() == null) {
            return TraceContext.getTraceId();
        }
        return defaultString(stringValue(callbackContext.state().get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.getTraceId());
    }

    /** 将可选状态值规范为字符串。 */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 将数值或数字字符串转为可见消息序号；非法值视为缺失。 */
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

    /** 只保留非空绑定 ID，拒绝非列表输入。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(item -> item != null)
                .map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    /** 首选非空主值，否则使用可信回退值。 */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
