package cn.bugstack.ai.domain.agent.service.armory.matter.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.SpringAI;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningAwareMessageConverter;
import cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning.ReasoningStreamingResponseAggregator;
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
 * Spring AI 模型到 ADK 模型接口的适配器，额外把「用了哪个模型、花了多少 Token」保住不丢。
 *
 * <p>解决什么问题：ADK 自带的适配在转换响应时会丢掉供应商返回的模型版本和 Token 用量，
 * 导致用量统计缺数据。这里在转换后把这些字段补回 ADK 响应，同时写进线程桥接点供日志插件读取。</p>
 *
 * <p>所属层次：领域层的装配辅料（模型适配器）。</p>
 *
 * <p>谁会调用它：{@code AgentNode} 在建每个 LlmAgent 时把真实模型包一层这个适配器，
 * 之后 ADK 每次调用模型都会走进来。</p>
 *
 * <p>它向下调用什么：Spring AI 的同步或流式聊天模型，以及 ADK 的请求响应转换器。</p>
 *
 * <p>它不负责什么：不写数据库、不做重试、不做限流。用量落库由日志插件负责。</p>
 */
public class ObservabilitySpringAI extends SpringAI {

    /**
     * 底层的 Spring AI 聊天模型，所有同步调用都打到它上面。
     */
    private final ChatModel chatModel;
    /**
     * 底层模型的流式能力引用；模型不支持流式时为空。
     *
     * <p>在构造时就判断好并固化下来，避免每次调用都做一次类型判断。
     * 为空时流式请求会明确返回错误，而不是悄悄降级成同步——那样前端会看到「不流式了」却不知道原因。</p>
     */
    private final StreamingChatModel streamingChatModel;
    /**
     * ADK 请求响应与 Spring AI 对象之间的转换器。
     *
     * <p>每个适配器实例持有一份；它是无状态的，多线程并发调用是安全的。</p>
     */
    private final ReasoningAwareMessageConverter messageConverter;
    /**
     * 装配时配置的模型代码，作为模型版本的最后一道兜底。
     *
     * <p>供应商响应和请求里都没带模型名时用它，保证用量记录里的模型字段不会是空的。</p>
     */
    private final String configuredModelName;

    /**
     * 构造适配器，固化模型能力判断和兜底模型名。
     *
     * <p>把「是否支持流式」在这里判断一次并存下来，而不是每次调用再判断，
     * 既省掉重复的类型检查，也让流式能力在对象生命周期内保持稳定。</p>
     */
    public ObservabilitySpringAI(ChatModel chatModel, String configuredModelName) {
        // 先交给父类完成 ADK 侧的标准初始化。
        super(chatModel, configuredModelName);
        // 保存同步调用入口。
        this.chatModel = chatModel;
        // 判断底层模型是否同时具备流式能力。
        if (chatModel instanceof StreamingChatModel) {
            // 支持流式，保存引用供流式分支使用。
            this.streamingChatModel = (StreamingChatModel) chatModel;
        } else {
            // 不支持就置空，流式请求会据此明确报错而不是静默降级。
            this.streamingChatModel = null;
        }
        // 建立请求响应转换器，负责 ADK 与 Spring AI 之间的结构互转。
        this.messageConverter = new ReasoningAwareMessageConverter(new ObjectMapper());
        // 记下配置的模型代码，作为模型版本的最终兜底值。
        this.configuredModelName = configuredModelName;
    }

    /**
     * ADK 调用模型的统一入口，按调用方要求分流到同步或流式实现。
     *
     * <p>刻意不做隐式降级：调用方要流式就必须真流式，模型不支持时直接报错，
     * 否则前端表现为「一直等到全部生成完才出字」，问题却查不出原因。</p>
     */
    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean streaming) {
        // 调用方明确决定同步或流式，不做隐式能力降级。
        if (streaming) {
            // 走流式分支，边生成边发。
            return generateStreamingContent(llmRequest);
        }

        // 走同步分支，一次性拿到完整响应。
        return generateContent(llmRequest);
    }

    /**
     * 建立双向实时连接（语音等场景），直接沿用 ADK 原生实现。
     *
     * <p>这条路径上没有需要补的观测字段，因此不做任何包装，避免引入不必要的差异。</p>
     */
    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        // 双向连接语义沿用 ADK 原生实现。
        return super.connect(llmRequest);
    }

    /**
     * 执行一次同步模型调用，并把观测字段补回响应。
     *
     * <p>数据流：ADK 请求 → 清理线程残留 → 转成 Spring AI 提示 → 调用模型 → 转回 ADK 响应
     * → 补上模型版本与 Token 用量 → 包成单元素流返回。</p>
     *
     * <p>为什么先清理线程残留：这个线程可能刚服务过别的调用。如果不清就直接开始，
     * 中途抛异常时日志插件会读到上一次的用量数据，把账记错。</p>
     *
     * <p>异常不往外抛而是转成错误流：调用方统一按流处理成功和失败，避免两套错误处理路径。</p>
     */
    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        // 转换和调用都可能抛异常，统一接住转成错误流交给下游。
        try {
            // 先清除线程残留，再进行任何可能抛错的转换或调用。
            ModelObservabilityContext.clear();
            // 把 ADK 请求转成 Spring AI 的提示对象。
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            // 真正发起一次同步模型调用，阻塞直到拿到完整响应。
            ChatResponse chatResponse = chatModel.call(prompt);
            // 把 Spring AI 响应转回 ADK 响应结构。
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
            // 补上模型版本和 Token 用量后包成单元素流返回。
            return Flowable.just(enrich(llmResponse, chatResponse, llmRequest));
        } catch (Exception e) {
            // 失败也走流的错误通道，让调用方只用一套处理逻辑。
            return Flowable.error(e);
        }
    }

    /**
     * 执行一次流式模型调用，把 Reactor 流桥接成支持取消的 RxJava 流。
     *
     * <p>各层职责：
     * 第一层：模型不支持流式就立刻返回错误流，不做降级。
     * 第二层：创建 RxJava 流，在订阅时才真正发起模型调用。
     * 第三层：对每个分片做三件事——聚合内容、补观测字段、记住最新一帧。
     * 第四层：流正常结束时补发一次终帧，把最后分片的观测字段搬到聚合结果上。
     * 第五层：注册取消动作，下游一断开就停止向供应商拉数据。</p>
     *
     * <p>数据流：
     * ADK 请求
     * → 清理线程残留
     * → 转成 Spring AI 提示
     * → 订阅供应商流
     * → 每个分片：转成 ADK 响应 → 聚合累计内容 → 补模型版本与用量 → 记为最新帧 → 发给下游
     * → 流结束：把最新帧的观测字段复制到聚合终帧 → 发出终帧 → 通知完成
     * → 下游取消：释放供应商订阅</p>
     *
     * <p>为什么要单独补终帧：聚合器产出的终帧只保证内容完整，模型版本和 Token 这些元数据
     * 往往只挂在最后一个分片上，不补就会丢掉整次调用的用量。</p>
     *
     * <p>背压策略选缓冲：模型输出速度不受我们控制，下游消费慢时先缓冲而不是丢弃或报错，
     * 代价是输出极长时内存占用会上升。</p>
     */
    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        // 第一层：底层模型没有流式能力，明确报错而不是偷偷改成同步。
        if (streamingChatModel == null) {
            // 返回错误流，调用方能看到确切原因。
            return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
        }

        // 第二层：创建惰性流，只有被订阅时才真正调用模型。
        return Flowable.create(emitter -> {
            // 清除本线程可能残留的上一次观测数据。
            ModelObservabilityContext.clear();
            // 把 ADK 请求转成 Spring AI 提示。
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            // 聚合器负责把一串分片拼成完整内容。
            ReasoningStreamingResponseAggregator aggregator = new ReasoningStreamingResponseAggregator();
            // 记住最近一帧已补齐观测字段的响应，流结束时要从它身上把元数据搬到终帧。
            AtomicReference<LlmResponse> latestResponse = new AtomicReference<>();

            // 订阅供应商流，拿到句柄以便下游取消时释放。
            Disposable disposable = streamingChatModel.stream(prompt)
                    .map(chatResponse -> {
                        // 第三层：每个分片先聚合内容，再附加该分片可获得的最新元数据。
                        LlmResponse partial = messageConverter.toLlmResponse(chatResponse, true);
                        // 把这一片并入累计内容，得到「到目前为止」的完整响应。
                        LlmResponse aggregated = aggregator.process(partial);
                        // 补上模型版本和 Token 用量，同时写进线程桥接点。
                        LlmResponse enriched = enrich(aggregated, chatResponse, llmRequest);
                        // 记为最新一帧，供结束时补终帧使用。
                        latestResponse.set(enriched);
                        // 把这一帧交给下游。
                        return enriched;
                    })
                    .subscribe(
                            // 有数据就直接转发给下游订阅者。
                            emitter::onNext,
                            // 出错就把异常转发给下游，由调用方决定如何处理。
                            emitter::onError,
                            () -> {
                                // 第四层：聚合器终态可能丢失最后分片元数据，显式复制回来。
                                if (!aggregator.isEmpty()) {
                                    // 把最新帧的模型版本、用量和终止原因搬到聚合终帧上再发出去。
                                    emitter.onNext(copyTerminalMetadata(aggregator.finish(), latestResponse.get()));
                                }
                                // 通知下游流已正常结束。
                                emitter.onComplete();
                            }
                    );

            // 第五层：下游取消立即停止供应商流，不再产生无人消费的输出和费用。
            emitter.setCancellable(disposable::dispose);
        }, BackpressureStrategy.BUFFER);
    }

    /**
     * 把最新分片上的观测字段和终止信息复制到聚合出来的终帧上。
     *
     * <p>为什么需要：聚合器只负责拼内容，模型版本、Token 用量、终止原因这些字段通常只出现在
     * 最后一个分片里。不复制的话，一次调用的用量统计就会整体缺失。</p>
     *
     * <p>数据流：聚合终帧 + 最新分片 → 逐个可选字段存在则覆盖 → 返回补齐后的终帧。</p>
     *
     * <p>没有最新分片（流一开始就结束）时原样返回终帧，不构造空字段。</p>
     */
    private LlmResponse copyTerminalMetadata(LlmResponse terminal, LlmResponse latest) {
        // 一帧都没收到过，没有可复制的元数据，原样返回。
        if (latest == null) {
            // 直接返回聚合终帧。
            return terminal;
        }
        // 以终帧为基础做修改，保留它已经拼好的完整内容。
        LlmResponse.Builder builder = terminal.toBuilder();
        // 模型版本：有值才覆盖，避免用空值抹掉终帧里可能已有的值。
        latest.modelVersion().ifPresent(builder::modelVersion);
        // Token 用量：整次调用的计费依据，必须搬过来。
        latest.usageMetadata().ifPresent(builder::usageMetadata);
        // 终止原因：用于判断是正常结束还是被截断。
        latest.finishReason().ifPresent(builder::finishReason);
        // 错误码：供应商侧的失败标识。
        latest.errorCode().ifPresent(builder::errorCode);
        // 错误信息：供应商侧的失败说明。
        latest.errorMessage().ifPresent(builder::errorMessage);
        // 是否被打断：区分「模型说完了」和「被中途掐断」。
        latest.interrupted().ifPresent(builder::interrupted);
        // 返回补齐元数据的终帧。
        return builder.build();
    }

    /**
     * 从供应商响应里抽出观测字段，既补回 ADK 响应，也写进线程桥接点。
     *
     * <p>为什么要写两份：ADK 响应是给下游业务用的，线程桥接点是给日志插件用的。
     * 有些情况下框架会在后续处理中丢掉响应上的元数据，桥接点是兜底通道。</p>
     *
     * <p>数据流：ADK 响应 + 供应商响应 + 原始请求 → 解析模型版本 → 解析 Token 用量
     * → 有值则写回响应 → 同时写入线程桥接点 → 返回补齐后的响应。</p>
     *
     * <p>解析不到就不写：宁可字段缺失，也不要伪造一个零值让用量统计看起来「有数据」。</p>
     */
    private LlmResponse enrich(LlmResponse llmResponse, ChatResponse chatResponse, LlmRequest llmRequest) {
        // 以原响应为基础做增量修改。
        LlmResponse.Builder builder = llmResponse.toBuilder();

        // 按优先级解析出这次实际使用的模型版本。
        String modelVersion = extractModelVersion(chatResponse, llmRequest);
        // 只有真的解析到才写回，避免用空串覆盖。
        if (modelVersion != null && !modelVersion.isBlank()) {
            // 写回模型版本，用量记录和日志都读它。
            builder.modelVersion(modelVersion);
        }

        // 把 Spring AI 的用量结构标准化成 ADK 用量对象。
        GenerateContentResponseUsageMetadata usageMetadata = extractUsageMetadata(chatResponse);
        // 解析不到就不写，保持「无数据」而不是「零数据」。
        if (usageMetadata != null) {
            // 写回 Token 用量，它是计费和成本分析的依据。
            builder.usageMetadata(usageMetadata);
        }

        // 同时写进线程桥接点，作为日志插件读不到响应元数据时的兜底通道。
        ModelObservabilityContext.set(modelVersion, usageMetadata);

        // 返回补齐观测字段的响应。
        return builder.build();
    }

    /**
     * 按可信度顺序确定这次调用实际用的模型版本。
     *
     * <p>顺序是有讲究的：供应商响应里的模型名最真实（可能和请求不同，比如被路由到了别的版本）；
     * 其次是请求里显式指定的；最后才是装配时配置的。这样用量记录反映的是真实发生的事，
     * 而不是我们以为会发生的事。</p>
     *
     * <p>数据流：供应商响应元数据 → 请求显式模型 → 装配配置模型 → 返回第一个有值的。</p>
     */
    private String extractModelVersion(ChatResponse chatResponse, LlmRequest llmRequest) {
        // 取供应商响应的元数据；响应为空时按无元数据处理。
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();

        // 首选供应商实际返回的模型名，它最接近真实。
        if (metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()) {
            // 直接返回供应商声明的模型名。
            return metadata.getModel();
        }

        // 其次用请求里显式指定的模型名。
        if (llmRequest != null && llmRequest.model().isPresent() && !llmRequest.model().get().isBlank()) {
            // 返回请求里写明的模型名。
            return llmRequest.model().get();
        }

        // 都没有就用装配配置的模型代码兜底，保证字段不为空。
        return configuredModelName;
    }

    /**
     * 把 Spring AI 的用量对象标准化成 ADK 的用量结构。
     *
     * <p>各层职责：
     * 第一层：取出供应商用量对象，没有就直接返回空。
     * 第二层：抽出五类 Token——输入、输出、总计、推理、工具提示。前三类是标准字段，
     *         后两类不同供应商放的位置不一样，需要反射兼容。
     * 第三层：五类全为空说明这次响应根本没带用量信息，返回空而不是造一个全零对象。
     * 第四层：逐个非空字段写进 ADK 用量对象。</p>
     *
     * <p>数据流：供应商响应 → 用量对象 → 抽取五类 Token → 全空则返回空 → 否则逐字段填充 → 返回标准用量。</p>
     *
     * <p>为什么不伪造零值：全零的用量记录和「真的没花 Token」无法区分，
     * 会让成本分析得出错误结论。缺失就应该显示为缺失。</p>
     */
    private GenerateContentResponseUsageMetadata extractUsageMetadata(ChatResponse chatResponse) {
        // 第一层：取供应商响应元数据。
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        // 从元数据里取用量对象。
        Usage usage = metadata == null ? null : metadata.getUsage();
        // 没有用量对象，本次调用无可记录的 Token 数据。
        if (usage == null) {
            // 返回空，让调用方保持字段缺失。
            return null;
        }

        // 第二层：输入 Token 数，是提示词部分的消耗。
        Integer promptTokens = usage.getPromptTokens();
        // 输出 Token 数，是模型生成部分的消耗。
        Integer candidateTokens = usage.getCompletionTokens();
        // 总计 Token 数，通常等于输入加输出，但部分供应商会另算。
        Integer totalTokens = usage.getTotalTokens();
        // 推理 Token 数：思考型模型特有，位置因供应商而异，靠反射兼容。
        Integer thoughtsTokens = extractThoughtsTokens(usage.getNativeUsage());
        // 工具提示 Token 数：工具定义占用的输入，同样需要反射兼容。
        Integer toolUsePromptTokens = extractToolUsePromptTokens(usage.getNativeUsage());

        // 第三层：五类全空说明响应没带任何用量信息。
        if (promptTokens == null
                && candidateTokens == null
                && totalTokens == null
                && thoughtsTokens == null
                && toolUsePromptTokens == null) {
            // 返回空而不是全零对象，避免把「没数据」伪装成「没花钱」。
            return null;
        }

        // 第四层：逐个把非空字段填进 ADK 用量结构。
        GenerateContentResponseUsageMetadata.Builder builder = GenerateContentResponseUsageMetadata.builder();
        // 有输入 Token 就写入。
        if (promptTokens != null) {
            // 写入提示词消耗。
            builder.promptTokenCount(promptTokens);
        }
        // 有输出 Token 就写入。
        if (candidateTokens != null) {
            // 写入生成内容消耗。
            builder.candidatesTokenCount(candidateTokens);
        }
        // 有总计 Token 就写入。
        if (totalTokens != null) {
            // 写入总消耗，计费通常按它算。
            builder.totalTokenCount(totalTokens);
        }
        // 有推理 Token 就写入。
        if (thoughtsTokens != null) {
            // 写入思考过程消耗，这部分往往单独计价。
            builder.thoughtsTokenCount(thoughtsTokens);
        }
        // 有工具提示 Token 就写入。
        if (toolUsePromptTokens != null) {
            // 写入工具定义占用的输入消耗。
            builder.toolUsePromptTokenCount(toolUsePromptTokens);
        }

        // 返回标准化后的用量对象。
        return builder.build();
    }

    /**
     * 找出推理（思考）Token 数，兼容不同供应商和版本的两种字段位置。
     *
     * <p>为什么这么麻烦：有的供应商把它平铺在用量根对象上，有的塞在 completionTokenDetails 子对象里，
     * 而且字段名也有两种写法。这里先试根对象的两个名字，不行再进子对象找。</p>
     *
     * <p>数据流：原生用量对象 → 试根对象两个候选名 → 命中则返回；否则取子对象 → 在子对象里找 → 返回结果或空。</p>
     */
    private Integer extractThoughtsTokens(Object nativeUsage) {
        // 先在根对象上试两种常见字段名。
        Integer direct = invokeIntegerMethod(nativeUsage, "reasoningTokens", "thoughtsTokenCount");
        // 命中就直接返回，不必再往子对象里找。
        if (direct != null) {
            // 返回根对象上找到的值。
            return direct;
        }

        // 根对象上没有，尝试取出细分子对象。
        Object completionTokenDetails = invokeObjectMethod(nativeUsage, "completionTokenDetails");
        // 在子对象里再找一次；仍找不到会返回空。
        return invokeIntegerMethod(completionTokenDetails, "reasoningTokens");
    }

    /**
     * 找出工具提示 Token 数，同样兼容两种字段位置和命名。
     *
     * <p>思路与推理 Token 一致：先在根对象上试，再进 promptTokenDetails 子对象找。</p>
     */
    private Integer extractToolUsePromptTokens(Object nativeUsage) {
        // 先在根对象上试两种常见字段名。
        Integer direct = invokeIntegerMethod(nativeUsage, "toolUsePromptTokens", "toolUsePromptTokenCount");
        // 命中就直接返回。
        if (direct != null) {
            // 返回根对象上找到的值。
            return direct;
        }

        // 根对象上没有，取出提示词细分子对象。
        Object promptTokenDetails = invokeObjectMethod(nativeUsage, "promptTokenDetails");
        // 在子对象里再按两种名字找一次。
        return invokeIntegerMethod(promptTokenDetails, "toolUsePromptTokens", "toolUsePromptTokenCount");
    }

    /**
     * 依次尝试若干候选方法名，返回第一个拿到的数值。
     *
     * <p>只接受数值类型的返回值：反射拿到的可能是任意对象，如果不加判断就强转，
     * 会因为一个无关的同名方法而抛异常，把整次模型响应带崩。</p>
     *
     * <p>全部候选都拿不到数值时返回空，由调用方按缺失处理。</p>
     */
    private Integer invokeIntegerMethod(Object target, String... methodNames) {
        // 按给定顺序逐个尝试候选方法名。
        for (String methodName : methodNames) {
            // 反射调用，失败会返回空而不是抛异常。
            Object value = invokeObjectMethod(target, methodName);
            // 只有确实是数值才认，防止同名但语义无关的方法造成误读。
            if (value instanceof Number number) {
                // 统一转成整型返回。
                return number.intValue();
            }
        }

        // 所有候选都没拿到数值，按缺失处理。
        return null;
    }

    /**
     * 用反射读取一个可选字段，读不到就返回空。
     *
     * <p>为什么用反射：这些 Token 细分字段属于各供应商的私有扩展，SDK 版本之间会增删改名。
     * 硬编码调用会导致换个依赖版本就编译不过或运行时崩掉。</p>
     *
     * <p>刻意吞掉所有异常：观测数据缺失只是少一点统计，而让模型响应因为读不到一个统计字段而失败，
     * 代价完全不成比例。</p>
     */
    private Object invokeObjectMethod(Object target, String methodName) {
        // 目标对象或方法名缺失时无从调用，直接返回空。
        if (target == null || methodName == null || methodName.isBlank()) {
            // 返回空，交由调用方按缺失处理。
            return null;
        }

        // 反射查找并调用无参方法；方法不存在或调用失败都在下面兜住。
        try {
            // 按名字找公开的无参方法。
            Method method = target.getClass().getMethod(methodName);
            // 调用并返回结果。
            return method.invoke(target);
        } catch (Exception ignore) {
            // 版本不兼容时返回 null 而非中断模型响应；观测字段缺失可以接受，响应失败不行。
            return null;
        }
    }
}
