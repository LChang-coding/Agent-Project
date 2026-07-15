package cn.bugstack.ai.domain.agent.service.armory.matter.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.StreamingResponseAggregator;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI adapter that preserves token usage/model metadata inside ADK LlmResponse.
 */
public class ObservabilitySpringAI extends SpringAI {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final MessageConverter messageConverter;
    private final String configuredModelName;

    public ObservabilitySpringAI(ChatModel chatModel, String configuredModelName) {
        super(chatModel, configuredModelName);
        this.chatModel = chatModel;
        if (chatModel instanceof StreamingChatModel) {
            this.streamingChatModel = (StreamingChatModel) chatModel;
        } else {
            this.streamingChatModel = null;
        }
        this.messageConverter = new MessageConverter(new ObjectMapper());
        this.configuredModelName = configuredModelName;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean streaming) {
        if (streaming) {
            return generateStreamingContent(llmRequest);
        }

        return generateContent(llmRequest);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        return super.connect(llmRequest);
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        try {
            ModelObservabilityContext.clear();
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            ChatResponse chatResponse = chatModel.call(prompt);
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
            return Flowable.just(enrich(llmResponse, chatResponse, llmRequest));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        if (streamingChatModel == null) {
            return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
        }

        return Flowable.create(emitter -> {
            ModelObservabilityContext.clear();
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
            AtomicReference<LlmResponse> latestResponse = new AtomicReference<>();

            Disposable disposable = streamingChatModel.stream(prompt)
                    .map(chatResponse -> {
                        LlmResponse partial = messageConverter.toLlmResponse(chatResponse, true);
                        LlmResponse aggregated = aggregator.processStreamingResponse(partial);
                        LlmResponse enriched = enrich(aggregated, chatResponse, llmRequest);
                        latestResponse.set(enriched);
                        return enriched;
                    })
                    .subscribe(
                            emitter::onNext,
                            emitter::onError,
                            () -> {
                                if (!aggregator.isEmpty()) {
                                    emitter.onNext(copyTerminalMetadata(aggregator.getFinalResponse(), latestResponse.get()));
                                }
                                emitter.onComplete();
                            }
                    );

            emitter.setCancellable(disposable::dispose);
        }, BackpressureStrategy.BUFFER);
    }

    private LlmResponse copyTerminalMetadata(LlmResponse terminal, LlmResponse latest) {
        if (latest == null) {
            return terminal;
        }
        LlmResponse.Builder builder = terminal.toBuilder();
        latest.modelVersion().ifPresent(builder::modelVersion);
        latest.usageMetadata().ifPresent(builder::usageMetadata);
        latest.finishReason().ifPresent(builder::finishReason);
        latest.errorCode().ifPresent(builder::errorCode);
        latest.errorMessage().ifPresent(builder::errorMessage);
        latest.interrupted().ifPresent(builder::interrupted);
        return builder.build();
    }

    private LlmResponse enrich(LlmResponse llmResponse, ChatResponse chatResponse, LlmRequest llmRequest) {
        LlmResponse.Builder builder = llmResponse.toBuilder();

        String modelVersion = extractModelVersion(chatResponse, llmRequest);
        if (modelVersion != null && !modelVersion.isBlank()) {
            builder.modelVersion(modelVersion);
        }

        GenerateContentResponseUsageMetadata usageMetadata = extractUsageMetadata(chatResponse);
        if (usageMetadata != null) {
            builder.usageMetadata(usageMetadata);
        }

        ModelObservabilityContext.set(modelVersion, usageMetadata);

        return builder.build();
    }

    private String extractModelVersion(ChatResponse chatResponse, LlmRequest llmRequest) {
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();

        if (metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()) {
            return metadata.getModel();
        }

        if (llmRequest != null && llmRequest.model().isPresent() && !llmRequest.model().get().isBlank()) {
            return llmRequest.model().get();
        }

        return configuredModelName;
    }

    private GenerateContentResponseUsageMetadata extractUsageMetadata(ChatResponse chatResponse) {
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) {
            return null;
        }

        Integer promptTokens = usage.getPromptTokens();
        Integer candidateTokens = usage.getCompletionTokens();
        Integer totalTokens = usage.getTotalTokens();
        Integer thoughtsTokens = extractThoughtsTokens(usage.getNativeUsage());
        Integer toolUsePromptTokens = extractToolUsePromptTokens(usage.getNativeUsage());

        if (promptTokens == null
                && candidateTokens == null
                && totalTokens == null
                && thoughtsTokens == null
                && toolUsePromptTokens == null) {
            return null;
        }

        GenerateContentResponseUsageMetadata.Builder builder = GenerateContentResponseUsageMetadata.builder();
        if (promptTokens != null) {
            builder.promptTokenCount(promptTokens);
        }
        if (candidateTokens != null) {
            builder.candidatesTokenCount(candidateTokens);
        }
        if (totalTokens != null) {
            builder.totalTokenCount(totalTokens);
        }
        if (thoughtsTokens != null) {
            builder.thoughtsTokenCount(thoughtsTokens);
        }
        if (toolUsePromptTokens != null) {
            builder.toolUsePromptTokenCount(toolUsePromptTokens);
        }

        return builder.build();
    }

    private Integer extractThoughtsTokens(Object nativeUsage) {
        Integer direct = invokeIntegerMethod(nativeUsage, "reasoningTokens", "thoughtsTokenCount");
        if (direct != null) {
            return direct;
        }

        Object completionTokenDetails = invokeObjectMethod(nativeUsage, "completionTokenDetails");
        return invokeIntegerMethod(completionTokenDetails, "reasoningTokens");
    }

    private Integer extractToolUsePromptTokens(Object nativeUsage) {
        Integer direct = invokeIntegerMethod(nativeUsage, "toolUsePromptTokens", "toolUsePromptTokenCount");
        if (direct != null) {
            return direct;
        }

        Object promptTokenDetails = invokeObjectMethod(nativeUsage, "promptTokenDetails");
        return invokeIntegerMethod(promptTokenDetails, "toolUsePromptTokens", "toolUsePromptTokenCount");
    }

    private Integer invokeIntegerMethod(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeObjectMethod(target, methodName);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }

        return null;
    }

    private Object invokeObjectMethod(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignore) {
            return null;
        }
    }
}
