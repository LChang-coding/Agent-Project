package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.armory.matter.model.ModelObservabilityContext;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.TraceContext;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.LoggingPlugin;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** 记录模型调用链路和 Token 用量；不记录提示词或回答正文。 */
@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {

    /** 以幂等调用记录闭环 running、success、failed 状态。 */
    private final ModelUsageService modelUsageService;
    /** 同一 invocation/Agent 可能串行多次调用模型，队列保持 callId 对应顺序。 */
    private final Map<String, ConcurrentLinkedDeque<String>> activeCallIds = new ConcurrentHashMap<>();
    /** 去重流式响应重复携带的累计 Token 快照。 */
    private final Map<String, String> tokenLogFingerprints = new ConcurrentHashMap<>();
    /** 记录模型调用单调时钟起点。 */
    private final Map<String, Long> modelCallStartedNanos = new ConcurrentHashMap<>();

    /** 注入用量服务；插件名称沿用 LoggingPlugin 默认实现。 */
    public MyLogPlugin(ModelUsageService modelUsageService) {
        this.modelUsageService = modelUsageService;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder requestBuilder) {
        // 先建立 callId 和计时起点，再调用基础日志回调。
        activeCallIds.computeIfAbsent(callScope(callbackContext), key -> new ConcurrentLinkedDeque<>())
                .addLast("call_" + UUID.randomUUID());
        modelCallStartedNanos.put(callScope(callbackContext), System.nanoTime());
        String requestedModel = requestBuilder.build().model().orElse("unknown");
        withCallbackTrace(callbackContext, () -> AiLog.info(AiLog.model().callStarted(
                callbackContext.userId(), callbackContext.sessionId(), callbackContext.agentName(),
                callbackContext.invocationContext().appName(), callbackContext.invocationId(), requestedModel)
                .field("tenantId", value(callbackContext.state().get(ToolRuntimeContextKeys.TENANT_ID)))
                .field("runId", value(callbackContext.state().get(ToolRuntimeContextKeys.RUN_ID)))));
        // running 记录保证调用中断时仍有可追踪事实。
        recordUsage(callbackContext, null, requestedModel, null,
                "running", null);
        return super.beforeModelCallback(callbackContext, requestBuilder)
                .doOnError(error -> {
                    // 基础 before 回调自身失败也必须收口本次用量状态。
                    recordUsage(callbackContext, null, "unknown", null, "failed",
                            error.getClass().getSimpleName());
                    finishCall(callbackContext);
                });
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        return super.afterModelCallback(callbackContext, llmResponse)
                .doOnEvent((ignored, throwable) -> {
                    if (throwable != null) {
                        ModelObservabilityContext.clear();
                        log.debug("Skipping token_usage observability log because the base logging callback failed", throwable);
                        return;
                    }

                    // ADK 响应优先；适配器 ThreadLocal 只补偿框架丢失的元数据。
                    ModelObservabilityContext.Snapshot snapshot = ModelObservabilityContext.get();
                    GenerateContentResponseUsageMetadata usageMetadata =
                            llmResponse.usageMetadata().orElse(snapshot == null ? null : snapshot.usageMetadata());
                    String modelVersion = llmResponse.modelVersion().orElse(snapshot == null ? "" : snapshot.modelVersion());

                    if (hasMeasuredUsage(usageMetadata) && tokenUsageChanged(callbackContext, usageMetadata)) {
                        // 结构化日志只在累计值变化时输出，避免每个流片重复。
                        withCallbackTrace(callbackContext, () -> AiLog.info(AiLog.model().tokenUsage(
                                callbackContext.userId(),
                                callbackContext.sessionId(),
                                callbackContext.agentName(),
                                callbackContext.invocationContext().appName(),
                                callbackContext.invocationId(),
                                modelVersion,
                                usageMetadata.promptTokenCount().orElse(null),
                                usageMetadata.candidatesTokenCount().orElse(null),
                                usageMetadata.totalTokenCount().orElse(null),
                                usageMetadata.thoughtsTokenCount().orElse(null),
                                usageMetadata.toolUsePromptTokenCount().orElse(null),
                                llmResponse.partial().orElse(null),
                                llmResponse.turnComplete().orElse(null))));
                    }

                    if (terminal(llmResponse)) {
                        // 终帧同时记录总耗时和成功用量，并释放调用级缓存。
                        withCallbackTrace(callbackContext, () -> AiLog.info(AiLog.model().call(
                                callbackContext.userId(), callbackContext.sessionId(),
                                callbackContext.agentName(), callbackContext.invocationContext().appName(),
                                callbackContext.invocationId(), modelVersion,
                                elapsedModelCallMs(callbackContext), true)));
                        recordUsage(callbackContext, llmResponse, modelVersion, usageMetadata, "success", null);
                        finishCall(callbackContext);
                        tokenLogFingerprints.remove(callScope(callbackContext));
                        modelCallStartedNanos.remove(callScope(callbackContext));
                    } else if (hasMeasuredUsage(usageMetadata)) {
                        // 中间响应若携带供应商累计 usage，单调落库但不重复刷结构化日志。
                        recordUsage(callbackContext, llmResponse, modelVersion, usageMetadata, "running", null);
                    }

                    // 每个回调结束清理线程桥接值，防止线程复用污染下次调用。
                    ModelObservabilityContext.clear();
                });
    }

    @Override
    public Maybe<LlmResponse> onModelErrorCallback(CallbackContext callbackContext,
                                                   LlmRequest.Builder requestBuilder,
                                                   Throwable throwable) {
        return super.onModelErrorCallback(callbackContext, requestBuilder, throwable)
                .doOnEvent((ignored, callbackError) -> {
                    if (callbackError != null) {
                        ModelObservabilityContext.clear();
                        log.debug("Skipping model_error observability log because the base logging callback failed", callbackError);
                        return;
                    }

                    // 错误响应可能只在线程桥接上下文中保留供应商模型与部分用量。
                    ModelObservabilityContext.Snapshot snapshot = ModelObservabilityContext.get();
                    withCallbackTrace(callbackContext, () -> AiLog.error(AiLog.model().error(
                            callbackContext.userId(),
                            callbackContext.sessionId(),
                            callbackContext.agentName(),
                            callbackContext.invocationContext().appName(),
                            callbackContext.invocationId(),
                            snapshot == null ? "" : snapshot.modelVersion(),
                            throwable)));
                    recordUsage(callbackContext, null, snapshot == null ? "" : snapshot.modelVersion(),
                            snapshot == null ? null : snapshot.usageMetadata(), "failed",
                            throwable == null ? "model_error" : throwable.getClass().getSimpleName());
                    finishCall(callbackContext);
                    tokenLogFingerprints.remove(callScope(callbackContext));
                    modelCallStartedNanos.remove(callScope(callbackContext));
                    ModelObservabilityContext.clear();
                });
    }

    /** 完成标记或 finishReason 任一存在即视为模型终帧。 */
    private boolean terminal(LlmResponse response) {
        return response.turnComplete().orElse(false) || response.finishReason().isPresent();
    }

    /** 只有至少一个核心 Token 大于零才输出用量事件。 */
    private boolean hasMeasuredUsage(GenerateContentResponseUsageMetadata usage) {
        return usage != null && (usage.totalTokenCount().orElse(0) > 0
                || usage.promptTokenCount().orElse(0) > 0
                || usage.candidatesTokenCount().orElse(0) > 0);
    }

    /** 按五类 Token 生成指纹，识别供应商重复累计快照。 */
    private boolean tokenUsageChanged(CallbackContext context, GenerateContentResponseUsageMetadata usage) {
        String fingerprint = usage.promptTokenCount().orElse(0) + ":"
                + usage.candidatesTokenCount().orElse(0) + ":"
                + usage.totalTokenCount().orElse(0) + ":"
                + usage.thoughtsTokenCount().orElse(0) + ":"
                + usage.toolUsePromptTokenCount().orElse(0);
        return !fingerprint.equals(tokenLogFingerprints.put(callScope(context), fingerprint));
    }

    /** 使用单调时钟计算耗时；缺失起点时返回零。 */
    private long elapsedModelCallMs(CallbackContext context) {
        Long started = modelCallStartedNanos.get(callScope(context));
        if (started == null) {
            return 0L;
        }
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /** 临时切换到调用 state 的 traceId，执行后恢复线程原值。 */
    private void withCallbackTrace(CallbackContext callbackContext, Runnable action) {
        String previousTraceId = TraceContext.getTraceId();
        String callbackTraceId = extractTraceId(callbackContext);

        if (callbackTraceId != null && !callbackTraceId.isBlank()) {
            TraceContext.setTraceId(callbackTraceId);
        }

        try {
            action.run();
        } finally {
            if (previousTraceId == null || previousTraceId.isBlank()) {
                TraceContext.clear();
            } else {
                TraceContext.setTraceId(previousTraceId);
            }
        }
    }

    /** 从 ADK session state 或 callback data 中读取入口 traceId。 */
    private String extractTraceId(CallbackContext callbackContext) {
        if (callbackContext == null || callbackContext.invocationContext() == null) {
            return null;
        }

        Object traceId = null;
        if (callbackContext.invocationContext().session() != null
                && callbackContext.invocationContext().session().state() != null) {
            traceId = callbackContext.invocationContext().session().state().get(TraceContext.TRACE_ID_STATE_KEY);
        }

        if (traceId == null && callbackContext.invocationContext().callbackContextData() != null) {
            traceId = callbackContext.invocationContext().callbackContextData().get(TraceContext.TRACE_ID_STATE_KEY);
        }

        return traceId == null ? null : String.valueOf(traceId);
    }

    /** 最佳努力写用量；观测故障不能反向导致模型调用失败。 */
    private void recordUsage(CallbackContext context, LlmResponse response, String modelVersion,
                             GenerateContentResponseUsageMetadata usage, String status, String finishReason) {
        try {
            Map<String, Object> state = context.state();
            String reason = finishReason;
            if (reason == null && response != null) {
                reason = response.finishReason().map(Object::toString).orElse(null);
            }
            // 身份取可信 state，缺失用户时才回退 ADK callback 用户。
            modelUsageService.record(ModelUsageEntity.builder()
                    .tenantId(value(state.get(ToolRuntimeContextKeys.TENANT_ID)))
                    .userId(defaultValue(value(state.get(ToolRuntimeContextKeys.USER_ID)), context.userId()))
                    .sessionId(value(state.get(ToolRuntimeContextKeys.SESSION_ID)))
                    .runId(value(state.get(ToolRuntimeContextKeys.RUN_ID)))
                    .callId(currentCallId(context))
                    .invocationId(context.invocationId())
                    .agentId(defaultValue(value(state.get(ToolRuntimeContextKeys.WORKFLOW_ID)), context.agentName()))
                    .agentName(context.agentName())
                    .appName(context.invocationContext().appName()).provider("spring-ai")
                    .modelVersion(defaultValue(modelVersion, "unknown")).usageType("chat")
                    .callStatus(status).finishReason(reason)
                    .promptTokens(usage == null ? null : usage.promptTokenCount().orElse(null))
                    .candidateTokens(usage == null ? null : usage.candidatesTokenCount().orElse(null))
                    .totalTokens(usage == null ? null : usage.totalTokenCount().orElse(null))
                    .thoughtsTokens(usage == null ? null : usage.thoughtsTokenCount().orElse(null))
                    .toolUsePromptTokens(usage == null ? null : usage.toolUsePromptTokenCount().orElse(null))
                    .traceId(extractTraceId(context)).build());
        } catch (Exception e) {
            // 用量是旁路观测，持久化失败只告警。
            log.warn("模型用量落库失败 invocationId:{}", context.invocationId(), e);
        }
    }

    /** invocationId 与 Agent 名共同隔离嵌套调用。 */
    private String callScope(CallbackContext context) {
        return context.invocationId() + ":" + context.agentName();
    }

    /** 返回队首 callId；生命周期异常缺失时补建，保证用量仍可幂等落库。 */
    private String currentCallId(CallbackContext context) {
        ConcurrentLinkedDeque<String> calls = activeCallIds.computeIfAbsent(callScope(context),
                key -> new ConcurrentLinkedDeque<>());
        String callId = calls.peekFirst();
        if (callId != null) {
            return callId;
        }
        callId = "call_" + UUID.randomUUID();
        calls.addLast(callId);
        return callId;
    }

    /** 结束当前队首调用，队列清空后移除作用域。 */
    private void finishCall(CallbackContext context) {
        String scope = callScope(context);
        ConcurrentLinkedDeque<String> calls = activeCallIds.get(scope);
        if (calls == null) {
            return;
        }
        calls.pollFirst();
        if (calls.isEmpty()) {
            activeCallIds.remove(scope, calls);
        }
    }

    /** 将可选状态值规范为字符串。 */
    private String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 空值回退到调用上下文提供的标识。 */
    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
