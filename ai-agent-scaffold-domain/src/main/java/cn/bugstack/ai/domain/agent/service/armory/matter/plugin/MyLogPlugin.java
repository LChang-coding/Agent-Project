package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.armory.matter.model.ModelObservabilityContext;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.LoggingPlugin;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {

    private static final Logger OBSERVABILITY_LOG = LoggerFactory.getLogger("observability");
    private static final int MAX_MESSAGE_LENGTH = 512;

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
                        OBSERVABILITY_LOG.info(buildTokenUsageLog(callbackContext, modelVersion, llmResponse, usageMetadata));
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

                    OBSERVABILITY_LOG.error(buildModelErrorLog(callbackContext, throwable));
                    ModelObservabilityContext.clear();
                });
    }

    private String buildTokenUsageLog(CallbackContext callbackContext,
                                      String modelVersion,
                                      LlmResponse llmResponse,
                                      GenerateContentResponseUsageMetadata usageMetadata) {
        StringBuilder builder = new StringBuilder("event=token_usage");
        appendQuoted(builder, "userId", callbackContext.userId());
        appendQuoted(builder, "sessionId", callbackContext.sessionId());
        appendQuoted(builder, "agentName", callbackContext.agentName());
        appendQuoted(builder, "appName", callbackContext.invocationContext().appName());
        appendQuoted(builder, "invocationId", callbackContext.invocationId());
        appendQuoted(builder, "modelVersion", modelVersion == null ? "" : modelVersion);
        appendNumber(builder, "promptTokens", usageMetadata.promptTokenCount().orElse(null));
        appendNumber(builder, "candidateTokens", usageMetadata.candidatesTokenCount().orElse(null));
        appendNumber(builder, "totalTokens", usageMetadata.totalTokenCount().orElse(null));
        appendNumber(builder, "thoughtsTokens", usageMetadata.thoughtsTokenCount().orElse(null));
        appendNumber(builder, "toolUsePromptTokens", usageMetadata.toolUsePromptTokenCount().orElse(null));
        appendBoolean(builder, "partial", llmResponse.partial().orElse(null));
        appendBoolean(builder, "turnComplete", llmResponse.turnComplete().orElse(null));
        return builder.toString();
    }

    private String buildModelErrorLog(CallbackContext callbackContext, Throwable throwable) {
        StringBuilder builder = new StringBuilder("event=model_error");
        appendQuoted(builder, "userId", callbackContext.userId());
        appendQuoted(builder, "sessionId", callbackContext.sessionId());
        appendQuoted(builder, "agentName", callbackContext.agentName());
        appendQuoted(builder, "appName", callbackContext.invocationContext().appName());
        appendQuoted(builder, "invocationId", callbackContext.invocationId());
        appendQuoted(builder, "errorType", throwable == null ? "" : throwable.getClass().getSimpleName());
        appendQuoted(builder, "errorMessage", throwable == null ? "" : truncate(throwable.getMessage()));
        return builder.toString();
    }

    private void appendQuoted(StringBuilder builder, String key, String value) {
        builder.append(' ')
                .append(key)
                .append("=\"")
                .append(escape(value))
                .append('"');
    }

    private void appendNumber(StringBuilder builder, String key, Integer value) {
        builder.append(' ')
                .append(key)
                .append('=')
                .append(value == null ? "null" : value);
    }

    private void appendBoolean(StringBuilder builder, String key, Boolean value) {
        builder.append(' ')
                .append(key)
                .append('=')
                .append(value == null ? "null" : value);
    }

    private String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_MESSAGE_LENGTH) {
            return value;
        }

        return value.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }
}
