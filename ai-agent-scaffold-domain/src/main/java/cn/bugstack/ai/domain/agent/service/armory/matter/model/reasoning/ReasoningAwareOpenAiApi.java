package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiStreamFunctionCallingHelper;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring AI OpenAiApi 的隔离兼容层。原框架 DTO 不声明 reasoning_content，本类在 JSON 边界
 * 归一化字段后再交回原有 ChatModel，因此无需侵入工具执行、重试和观测实现。
 */
public final class ReasoningAwareOpenAiApi extends OpenAiApi {

    private static final String DONE = "[DONE]";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final WebClient webClient;
    private final String completionsPath;
    private final ReasoningModelAdapter adapter;
    private final ReasoningMode mode;
    private final boolean fallbackDisabled;
    private final OpenAiStreamFunctionCallingHelper chunkMerger = new OpenAiStreamFunctionCallingHelper();

    public ReasoningAwareOpenAiApi(String baseUrl, String apiKey, String completionsPath, String embeddingsPath,
                                   RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder,
                                   ObjectMapper objectMapper, ReasoningModelAdapter adapter, ReasoningMode mode,
                                   boolean fallbackDisabled) {
        super(baseUrl, new SimpleApiKey(apiKey), new LinkedMultiValueMap<>(), completionsPath, embeddingsPath,
                restClientBuilder, webClientBuilder, RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        this.objectMapper = objectMapper;
        this.completionsPath = completionsPath;
        this.adapter = adapter;
        this.mode = mode;
        this.fallbackDisabled = fallbackDisabled;
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json").build();
        this.webClient = webClientBuilder.clone().baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json").build();
    }

    @Override
    public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request,
                                                                MultiValueMap<String, String> additionalHeaders) {
        try {
            return sync(request, additionalHeaders, mode);
        } catch (RuntimeException exception) {
            if (!fallbackDisabled || mode == ReasoningMode.DISABLED || !badRequest(exception)) throw exception;
            return sync(request, additionalHeaders, ReasoningMode.DISABLED);
        }
    }

    private ResponseEntity<ChatCompletion> sync(ChatCompletionRequest request,
                                                 MultiValueMap<String, String> additionalHeaders,
                                                 ReasoningMode effectiveMode) {
        ObjectNode body = requestBody(request, effectiveMode);
        ResponseEntity<JsonNode> response = restClient.post().uri(completionsPath)
                .headers(headers -> headers.addAll(additionalHeaders)).body(body).retrieve().toEntity(JsonNode.class);
        JsonNode normalized = normalize(response.getBody());
        ChatCompletion completion = objectMapper.convertValue(normalized, ChatCompletion.class);
        // RestClient#retrieve 已经会把非 2xx 响应转换为异常；这里固定构造成功响应，
        // 避免 ResponseEntity#getStatusCode 在 Spring 5/6 之间返回类型变化造成二进制不兼容。
        return ResponseEntity.ok().headers(response.getHeaders()).body(completion);
    }

    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request,
                                                           MultiValueMap<String, String> additionalHeaders) {
        Flux<ChatCompletionChunk> primary = stream(request, additionalHeaders, mode);
        if (!fallbackDisabled || mode == ReasoningMode.DISABLED) return primary;
        return primary.onErrorResume(error -> badRequest(error)
                ? stream(request, additionalHeaders, ReasoningMode.DISABLED) : Flux.error(error));
    }

    private Flux<ChatCompletionChunk> stream(ChatCompletionRequest request,
                                             MultiValueMap<String, String> additionalHeaders,
                                             ReasoningMode effectiveMode) {
        ObjectNode body = requestBody(request, effectiveMode);
        AtomicBoolean insideTool = new AtomicBoolean(false);
        return webClient.post().uri(completionsPath).headers(headers -> headers.addAll(additionalHeaders))
                .body(Mono.just(body), ObjectNode.class).retrieve().bodyToFlux(String.class)
                .takeUntil(DONE::equals).filter(value -> !DONE.equals(value))
                .map(value -> objectMapper.convertValue(normalize(read(value)), ChatCompletionChunk.class))
                .map(chunk -> {
                    if (chunkMerger.isStreamingToolFunctionCall(chunk)) insideTool.set(true);
                    return chunk;
                })
                .windowUntil(chunk -> {
                    if (insideTool.get() && chunkMerger.isStreamingToolFunctionCallFinish(chunk)) {
                        insideTool.set(false);
                        return true;
                    }
                    return !insideTool.get();
                })
                .concatMap(window -> window.reduce(
                        new ChatCompletionChunk(null, List.of(), null, null, null, null, null, null),
                        chunkMerger::merge));
    }

    private ObjectNode requestBody(ChatCompletionRequest request, ReasoningMode effectiveMode) {
        ObjectNode body = objectMapper.valueToTree(request);
        adapter.prepareRequest(body, effectiveMode);
        return body;
    }

    private JsonNode normalize(JsonNode value) {
        if (value instanceof ObjectNode object) adapter.normalizeResponse(object);
        return value;
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模型流返回了无法解析的 JSON", exception);
        }
    }

    private boolean badRequest(Throwable error) {
        return error instanceof WebClientResponseException.BadRequest
                || error instanceof org.springframework.web.client.HttpClientErrorException.BadRequest;
    }
}
