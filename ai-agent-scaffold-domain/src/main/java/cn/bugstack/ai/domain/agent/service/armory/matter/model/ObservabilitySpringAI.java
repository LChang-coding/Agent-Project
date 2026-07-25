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

/** Spring AI 到 ADK 的模型适配器；保留供应商模型版本和 Token 元数据。 */
public class ObservabilitySpringAI extends SpringAI {

    /** 同步模型调用入口。 */
    private final ChatModel chatModel;
    /** 模型同时实现流式接口时使用；否则流式请求明确失败。 */
    private final StreamingChatModel streamingChatModel;
    /** 负责 ADK 请求/响应与 Spring AI 对象互转。 */
    private final MessageConverter messageConverter;
    /** 供应商未返回模型名时的最后回退值。 */
    private final String configuredModelName;

    /** 固化模型能力和配置模型名，不在每次调用重新判断类型。 */
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
        // 调用方明确决定同步或流式，不做隐式能力降级。
        if (streaming) {
            return generateStreamingContent(llmRequest);
        }

        return generateContent(llmRequest);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        // 双向连接语义沿用 ADK 原生实现。
        return super.connect(llmRequest);
    }

    /** 执行一次同步模型调用，并把观测元数据写回 ADK 响应。 */
    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        try {
            // 先清除线程残留，再进行任何可能抛错的转换或调用。
            ModelObservabilityContext.clear();
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            ChatResponse chatResponse = chatModel.call(prompt);
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
            return Flowable.just(enrich(llmResponse, chatResponse, llmRequest));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    /** 把 Reactor 流桥接为支持取消的 RxJava 流，并累计完整 ADK 响应。 */
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
                        // 每个分片先聚合内容，再附加该分片可获得的最新元数据。
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
                                // 聚合器终态可能丢失最后分片元数据，显式复制回来。
                                if (!aggregator.isEmpty()) {
                                    emitter.onNext(copyTerminalMetadata(aggregator.getFinalResponse(), latestResponse.get()));
                                }
                                emitter.onComplete();
                            }
                    );

            // 下游取消立即停止供应商流。
            emitter.setCancellable(disposable::dispose);
        }, BackpressureStrategy.BUFFER);
    }

    /** 将最新分片的模型、Token 和终态字段复制到聚合终帧。 */
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

    /** 提取供应商观测字段，同时写入 ADK 响应和线程桥接快照。 */
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

    /** 模型名优先取供应商响应，其次请求显式模型，最后取装配配置。 */
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

    /** 将 Spring AI Usage 标准化为 ADK 用量对象；全部缺失时不伪造零值。 */
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

    /** 兼容不同供应商/版本对推理 Token 的两种对象层级。 */
    private Integer extractThoughtsTokens(Object nativeUsage) {
        Integer direct = invokeIntegerMethod(nativeUsage, "reasoningTokens", "thoughtsTokenCount");
        if (direct != null) {
            return direct;
        }

        Object completionTokenDetails = invokeObjectMethod(nativeUsage, "completionTokenDetails");
        return invokeIntegerMethod(completionTokenDetails, "reasoningTokens");
    }

    /** 兼容不同供应商/版本对工具提示 Token 的两种对象层级。 */
    private Integer extractToolUsePromptTokens(Object nativeUsage) {
        Integer direct = invokeIntegerMethod(nativeUsage, "toolUsePromptTokens", "toolUsePromptTokenCount");
        if (direct != null) {
            return direct;
        }

        Object promptTokenDetails = invokeObjectMethod(nativeUsage, "promptTokenDetails");
        return invokeIntegerMethod(promptTokenDetails, "toolUsePromptTokens", "toolUsePromptTokenCount");
    }

    /** 依次尝试候选方法名，只接受数值返回值。 */
    private Integer invokeIntegerMethod(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeObjectMethod(target, methodName);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }

        return null;
    }

    /** 反射读取可选原生用量字段；版本不兼容时返回 null 而非中断模型响应。 */
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
