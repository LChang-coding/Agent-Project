package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.armory.matter.model.ModelObservabilityContext;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.LoggingPlugin;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        return super.afterModelCallback(callbackContext, llmResponse)
                .doOnEvent((ignored, throwable) -> {
                    if (throwable != null) {
                        ModelObservabilityContext.clear();
                        log.debug("Skipping token_usage observability log because the base logging callback failed", throwable);
                        return;
                    }

                    ModelObservabilityContext.Snapshot snapshot = ModelObservabilityContext.get();
                    GenerateContentResponseUsageMetadata usageMetadata =
                            llmResponse.usageMetadata().orElse(snapshot == null ? null : snapshot.usageMetadata());
                    String modelVersion = llmResponse.modelVersion().orElse(snapshot == null ? "" : snapshot.modelVersion());

                    if (usageMetadata != null) {
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

                    ModelObservabilityContext.Snapshot snapshot = ModelObservabilityContext.get();
                    withCallbackTrace(callbackContext, () -> AiLog.error(AiLog.model().error(
                            callbackContext.userId(),
                            callbackContext.sessionId(),
                            callbackContext.agentName(),
                            callbackContext.invocationContext().appName(),
                            callbackContext.invocationId(),
                            snapshot == null ? "" : snapshot.modelVersion(),
                            throwable)));
                    ModelObservabilityContext.clear();
                });
    }

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
}
