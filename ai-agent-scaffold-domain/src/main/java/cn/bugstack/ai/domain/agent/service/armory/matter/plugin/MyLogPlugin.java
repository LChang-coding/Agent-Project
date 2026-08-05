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

/**
 * 记录每次模型调用的链路日志和 Token 用量，并把用量落库。
 *
 * <p>解决什么问题：模型调用是花钱的，必须知道每次调用属于哪个租户、哪个会话、哪次运行，
 * 花了多少 Token，成功还是失败。这些数据是成本核算和限额控制的唯一依据。</p>
 *
 * <p>所属层次：领域层的装配辅料（ADK 插件，继承框架自带的日志插件）。</p>
 *
 * <p>谁会调用它：ADK 在模型调用前、调用后、出错时分别回调它；由配置显式挂进 Runner 插件列表。</p>
 *
 * <p>它向下调用什么：{@code ModelUsageService} 把用量幂等落库；
 * 从 {@code ModelObservabilityContext} 读取适配器补充的模型版本和用量数据。</p>
 *
 * <p>它不负责什么：不记录提示词和回答正文——那些可能含敏感信息，而且体量巨大。
 * 也不做限额拦截，它只负责记录事实。</p>
 *
 * <p>三条终态路径（成功、失败、基础回调自身出错）都必须收口：写用量、结束调用记录、清缓存。
 * 漏掉任何一条都会在内存里留下永不释放的条目。</p>
 */
@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {

    /**
     * 用量落库服务，按 callId 幂等写入 running / success / failed 三种状态。
     *
     * <p>先写 running 再改终态，好处是调用中途进程崩溃也能看到「有一次调用开始了但没结束」，
     * 不会出现凭空消失的消费。</p>
     */
    private final ModelUsageService modelUsageService;
    /**
     * 每个「调用作用域」下正在进行的调用编号队列。
     *
     * <p>为什么要队列：同一个 invocation 里同一个 Agent 可能串行调用模型多次（比如工具调用后再问一次）。
     * 用队列先进先出，保证前后回调配对到同一个 callId，用量才不会记串。</p>
     *
     * <p>用并发容器是因为回调可能发生在不同线程上；条目必须在终态时移除，否则会持续占用内存。</p>
     */
    private final Map<String, ConcurrentLinkedDeque<String>> activeCallIds = new ConcurrentHashMap<>();
    /**
     * 每个调用作用域最近一次 Token 用量的指纹，用来去重。
     *
     * <p>为什么需要：流式响应的每一片都可能带上「累计」用量，数值往往完全一样。
     * 不去重的话一次调用会打出几十条内容相同的用量日志。</p>
     */
    private final Map<String, String> tokenLogFingerprints = new ConcurrentHashMap<>();
    /**
     * 每个调用作用域的计时起点，单位纳秒。
     *
     * <p>用单调时钟而不是墙上时钟，避免系统时间被校正时算出负数耗时。
     * 同样必须在终态时清理。</p>
     */
    private final Map<String, Long> modelCallStartedNanos = new ConcurrentHashMap<>();

    /**
     * 注入用量服务；插件名沿用父类的默认值。
     *
     * <p>不自定义名字是因为它由配置显式挂载，不需要像上下文和门禁插件那样被自动去重。</p>
     */
    public MyLogPlugin(ModelUsageService modelUsageService) {
        // 保存用量服务，三条终态路径都要用它写库。
        this.modelUsageService = modelUsageService;
    }

    /**
     * 模型调用开始前：分配调用编号、开始计时、写一条 running 用量。
     *
     * <p>各层职责：
     * 第一层：为本次调用生成唯一编号并入队，后续回调靠它找回同一次调用。
     * 第二层：记下单调时钟起点，终态时用它算总耗时。
     * 第三层：打一条「调用开始」结构化日志，带上租户和运行编号便于按维度检索。
     * 第四层：立刻写一条 running 用量记录，保证调用中断时也留有可追踪的事实。
     * 第五层：调用父类默认实现，并对「父类回调自身失败」这种边缘情况做收口。</p>
     *
     * <p>数据流：
     * 模型调用请求
     * → 生成 callId 入队
     * → 记录计时起点
     * → 打调用开始日志
     * → 写 running 用量（落库）
     * → 交给父类默认日志逻辑
     * → 父类失败则补写 failed 用量并结束调用记录</p>
     *
     * <p>返回父类结果，不短路模型调用。</p>
     */
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder requestBuilder) {
        // 第一、二层：先建立 callId 和计时起点，再调用基础日志回调。
        activeCallIds.computeIfAbsent(callScope(callbackContext), key -> new ConcurrentLinkedDeque<>())
                .addLast("call_" + UUID.randomUUID());
        // 记下单调时钟起点，终态时据此算总耗时。
        modelCallStartedNanos.put(callScope(callbackContext), System.nanoTime());
        // 取出这次请求指定的模型；取不到时用 unknown 占位，保证日志字段不为空。
        String requestedModel = requestBuilder.build().model().orElse("unknown");
        // 第三层：临时切到本次调用的链路标识再打日志，保证日志能串进入口请求的链路。
        withCallbackTrace(callbackContext, () -> AiLog.info(AiLog.model().callStarted(
                callbackContext.userId(), callbackContext.sessionId(), callbackContext.agentName(),
                callbackContext.invocationContext().appName(), callbackContext.invocationId(), requestedModel)
                .field("tenantId", value(callbackContext.state().get(ToolRuntimeContextKeys.TENANT_ID)))
                .field("runId", value(callbackContext.state().get(ToolRuntimeContextKeys.RUN_ID)))));
        // 第四层：running 记录保证调用中断时仍有可追踪事实。
        recordUsage(callbackContext, null, requestedModel, null,
                "running", null);
        // 第五层：交给父类完成标准日志逻辑。
        return super.beforeModelCallback(callbackContext, requestBuilder)
                .doOnError(error -> {
                    // 基础 before 回调自身失败也必须收口本次用量状态，否则这条 running 记录永远悬着。
                    recordUsage(callbackContext, null, "unknown", null, "failed",
                            error.getClass().getSimpleName());
                    // 出队并按需清掉作用域，防止内存里堆积无用条目。
                    finishCall(callbackContext);
                });
    }

    /**
     * 模型返回内容后：按需打用量日志，终帧时写成功用量并清理缓存。
     *
     * <p>各层职责：
     * 第一层：父类回调自身失败时只清线程桥接值并放弃观测，不能让观测问题影响正常响应。
     * 第二层：取观测数据。以 ADK 响应为准，响应里没有才用适配器写进线程桥接点的值兜底。
     * 第三层：只有确实测到用量、且数值和上次不同才打日志——流式响应会反复带同样的累计值。
     * 第四层：判断是不是终帧。终帧要打总耗时日志、写 success 用量，并清掉三份缓存。
     * 第五层：不是终帧但带了用量，就更新 running 记录（用量是累计的，写库单调递增），但不打日志。
     * 第六层：无论走哪条路径，最后都要清掉线程桥接值，防止线程复用污染下一次调用。</p>
     *
     * <p>数据流：
     * 模型响应（可能是流中的一片）
     * → 取 ADK 响应元数据，缺失则取线程桥接快照
     * → 用量指纹去重 → 变化了才打结构化日志
     * → 是终帧：打耗时日志 → 写 success 用量（落库）→ 出队 → 清指纹与计时缓存
     * → 非终帧且有用量：写 running 用量（落库）
     * → 清理线程桥接值</p>
     *
     * <p>返回父类结果，不改变响应内容。</p>
     */
    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        // 先让父类完成标准日志，再在事件回调里做用量处理。
        return super.afterModelCallback(callbackContext, llmResponse)
                .doOnEvent((ignored, throwable) -> {
                    // 第一层：父类回调自身失败，放弃本次观测但必须清掉线程桥接值。
                    if (throwable != null) {
                        // 清线程桥接值，避免污染下一次调用。
                        ModelObservabilityContext.clear();
                        // 用 debug 记录即可，这属于观测链路问题不是业务问题。
                        log.debug("Skipping token_usage observability log because the base logging callback failed", throwable);
                        // 直接返回，不做任何用量处理。
                        return;
                    }

                    // 第二层：ADK 响应优先；适配器 ThreadLocal 只补偿框架丢失的元数据。
                    ModelObservabilityContext.Snapshot snapshot = ModelObservabilityContext.get();
                    // Token 用量：响应里有就用响应的，没有才用桥接快照兜底。
                    GenerateContentResponseUsageMetadata usageMetadata =
                            llmResponse.usageMetadata().orElse(snapshot == null ? null : snapshot.usageMetadata());
                    // 模型版本：同样响应优先，桥接快照兜底，都没有则为空串保证字段不缺。
                    String modelVersion = llmResponse.modelVersion().orElse(snapshot == null ? "" : snapshot.modelVersion());

                    // 第三层：确实测到用量、且和上次不一样才打日志。
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

                    // 第四层：判断是否已经是本次调用的最后一帧。
                    if (terminal(llmResponse)) {
                        // 终帧同时记录总耗时和成功用量，并释放调用级缓存。
                        withCallbackTrace(callbackContext, () -> AiLog.info(AiLog.model().call(
                                callbackContext.userId(), callbackContext.sessionId(),
                                callbackContext.agentName(), callbackContext.invocationContext().appName(),
                                callbackContext.invocationId(), modelVersion,
                                elapsedModelCallMs(callbackContext), true)));
                        // 把状态推进为 success，这是计费口径上的最终记录。
                        recordUsage(callbackContext, llmResponse, modelVersion, usageMetadata, "success", null);
                        // 从队列里出队这次调用，让后续调用配对到自己的 callId。
                        finishCall(callbackContext);
                        // 清掉用量指纹缓存，下次调用重新开始比对。
                        tokenLogFingerprints.remove(callScope(callbackContext));
                        // 清掉计时起点，防止内存里堆积。
                        modelCallStartedNanos.remove(callScope(callbackContext));
                    } else if (hasMeasuredUsage(usageMetadata)) {
                        // 第五层：中间响应若携带供应商累计 usage，单调落库但不重复刷结构化日志。
                        recordUsage(callbackContext, llmResponse, modelVersion, usageMetadata, "running", null);
                    }

                    // 第六层：每个回调结束清理线程桥接值，防止线程复用污染下次调用。
                    ModelObservabilityContext.clear();
                });
    }

    /**
     * 模型调用失败时：打错误日志、写 failed 用量并清理缓存。
     *
     * <p>各层职责：
     * 第一层：父类回调自身失败时只清线程桥接值并放弃观测。
     * 第二层：从线程桥接点取模型版本和部分用量——失败响应通常不带这些字段，
     *         但适配器在调用模型时可能已经写进桥接点了，这部分消耗同样要计费。
     * 第三层：打错误日志，写 failed 用量，然后出队并清掉全部缓存。</p>
     *
     * <p>数据流：
     * 模型异常
     * → 取线程桥接快照（模型版本 + 部分用量）
     * → 打模型错误日志
     * → 写 failed 用量（落库，带失败原因）
     * → 出队 → 清指纹与计时缓存 → 清线程桥接值</p>
     *
     * <p>为什么失败也要记用量：调用失败前可能已经消耗了输入 Token，供应商照样计费。
     * 只记成功调用会导致账目对不上。</p>
     */
    @Override
    public Maybe<LlmResponse> onModelErrorCallback(CallbackContext callbackContext,
                                                   LlmRequest.Builder requestBuilder,
                                                   Throwable throwable) {
        // 先让父类完成标准错误日志，再在事件回调里收口用量。
        return super.onModelErrorCallback(callbackContext, requestBuilder, throwable)
                .doOnEvent((ignored, callbackError) -> {
                    // 第一层：父类回调自身失败，放弃观测但必须清掉线程桥接值。
                    if (callbackError != null) {
                        // 清线程桥接值，防止污染下一次调用。
                        ModelObservabilityContext.clear();
                        // 观测链路问题用 debug 记录即可。
                        log.debug("Skipping model_error observability log because the base logging callback failed", callbackError);
                        // 直接返回，不做用量处理。
                        return;
                    }

                    // 第二层：错误响应可能只在线程桥接上下文中保留供应商模型与部分用量。
                    ModelObservabilityContext.Snapshot snapshot = ModelObservabilityContext.get();
                    // 第三层：打模型错误日志，模型版本缺失时用空串保证字段不缺。
                    withCallbackTrace(callbackContext, () -> AiLog.error(AiLog.model().error(
                            callbackContext.userId(),
                            callbackContext.sessionId(),
                            callbackContext.agentName(),
                            callbackContext.invocationContext().appName(),
                            callbackContext.invocationId(),
                            snapshot == null ? "" : snapshot.modelVersion(),
                            throwable)));
                    // 写 failed 用量：失败前消耗的输入 Token 供应商照样计费，必须入账。
                    recordUsage(callbackContext, null, snapshot == null ? "" : snapshot.modelVersion(),
                            snapshot == null ? null : snapshot.usageMetadata(), "failed",
                            throwable == null ? "model_error" : throwable.getClass().getSimpleName());
                    // 出队本次调用，让后续调用配对到自己的 callId。
                    finishCall(callbackContext);
                    // 清掉用量指纹缓存。
                    tokenLogFingerprints.remove(callScope(callbackContext));
                    // 清掉计时起点，防止内存堆积。
                    modelCallStartedNanos.remove(callScope(callbackContext));
                    // 最后清线程桥接值，防止线程复用污染下一次调用。
                    ModelObservabilityContext.clear();
                });
    }

    /**
     * 判断一帧响应是不是本次调用的最后一帧。
     *
     * <p>两个信号任一出现即视为终帧：明确的完成标记，或供应商给出的终止原因。
     * 只看一个会漏——有的供应商只给终止原因，不给完成标记。</p>
     *
     * <p>判断错的后果很实际：判早了会提前写成功用量并清缓存，后续帧就找不到 callId；
     * 判晚了则用量记录一直停在 running。</p>
     */
    private boolean terminal(LlmResponse response) {
        // 完成标记或 finishReason 任一存在即视为模型终帧。
        return response.turnComplete().orElse(false) || response.finishReason().isPresent();
    }

    /**
     * 判断这份用量数据是否真的测到了东西。
     *
     * <p>要求三个核心 Token 里至少有一个大于零。全是零通常意味着供应商还没统计出来，
     * 此时打日志或落库只会产生一堆无意义的零记录。</p>
     */
    private boolean hasMeasuredUsage(GenerateContentResponseUsageMetadata usage) {
        // 总量、输入、输出任一大于零才算真的有数据。
        return usage != null && (usage.totalTokenCount().orElse(0) > 0
                || usage.promptTokenCount().orElse(0) > 0
                || usage.candidatesTokenCount().orElse(0) > 0);
    }

    /**
     * 判断用量数据和上次记录的是否不同，用于日志去重。
     *
     * <p>做法是把五类 Token 拼成一个指纹字符串存起来，下次比对。相同就说明供应商又发了一份
     * 完全一样的累计快照，不必重复打日志。</p>
     *
     * <p>注意 put 会同时写入新值并返回旧值，所以这个方法有副作用——它既是判断也是记录。</p>
     */
    private boolean tokenUsageChanged(CallbackContext context, GenerateContentResponseUsageMetadata usage) {
        // 按五类 Token 生成指纹，识别供应商重复累计快照。
        String fingerprint = usage.promptTokenCount().orElse(0) + ":"
                + usage.candidatesTokenCount().orElse(0) + ":"
                + usage.totalTokenCount().orElse(0) + ":"
                + usage.thoughtsTokenCount().orElse(0) + ":"
                + usage.toolUsePromptTokenCount().orElse(0);
        // 写入新指纹并拿回旧指纹；不相等说明数值变了，值得打一条日志。
        return !fingerprint.equals(tokenLogFingerprints.put(callScope(context), fingerprint));
    }

    /**
     * 算出本次模型调用已经花了多少毫秒。
     *
     * <p>用单调时钟差值，不受系统时间校正影响；再用 max 兜一层，保证不会出现负数耗时。</p>
     *
     * <p>找不到起点时返回 0：说明 before 回调没执行或缓存已被清理，宁可记 0 也不要抛异常。</p>
     */
    private long elapsedModelCallMs(CallbackContext context) {
        // 取出这次调用的计时起点。
        Long started = modelCallStartedNanos.get(callScope(context));
        // 起点缺失说明生命周期异常，返回 0 而不是报错。
        if (started == null) {
            // 返回零耗时。
            return 0L;
        }
        // 用单调时钟算差值并转成毫秒；max 兜底避免负数。
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /**
     * 临时把线程的链路标识切换成本次调用的，执行完再还原。
     *
     * <p>为什么必须这样：模型回调常常在线程池的别的线程上执行，那个线程里的链路标识可能属于
     * 另一个请求。不切换就会把这条日志挂到别人的链路上，排查时完全对不上。</p>
     *
     * <p>数据流：保存线程原值 → 取本次调用的链路标识 → 有值则切换 → 执行日志动作
     * → finally 还原（原值为空则清空）。</p>
     *
     * <p>还原放在 finally 里：日志动作抛异常也必须还原，否则这个线程后续所有日志都会带错标识。</p>
     */
    private void withCallbackTrace(CallbackContext callbackContext, Runnable action) {
        // 先记下线程当前的链路标识，结束时要还原。
        String previousTraceId = TraceContext.getTraceId();
        // 取出本次调用真正的链路标识。
        String callbackTraceId = extractTraceId(callbackContext);

        // 只有确实取到才切换，避免用空值把线程原有标识抹掉。
        if (callbackTraceId != null && !callbackTraceId.isBlank()) {
            // 切换到本次调用的链路标识。
            TraceContext.setTraceId(callbackTraceId);
        }

        // 执行日志动作，无论成败都要还原线程状态。
        try {
            // 真正打日志。
            action.run();
        } finally {
            // 原值本来就没有，就清空而不是写回空串。
            if (previousTraceId == null || previousTraceId.isBlank()) {
                // 清空线程链路标识。
                TraceContext.clear();
            } else {
                // 还原成进入前的值。
                TraceContext.setTraceId(previousTraceId);
            }
        }
    }

    /**
     * 从 ADK 会话状态或回调数据里找出入口请求的链路标识。
     *
     * <p>两个地方都要看：普通对话把它放在会话状态里，某些回调路径只有回调数据。
     * 只看一处会导致部分日志丢失链路信息。</p>
     *
     * <p>数据流：回调上下文 → 会话状态里找 → 没找到则回调数据里找 → 转成字符串返回或返回空。</p>
     */
    private String extractTraceId(CallbackContext callbackContext) {
        // 上下文缺失时无从查找。
        if (callbackContext == null || callbackContext.invocationContext() == null) {
            // 返回空，调用方会保留线程原有标识。
            return null;
        }

        // 先置空，下面依次尝试两个来源。
        Object traceId = null;
        // 优先从 ADK 会话状态里取，这是普通对话的正常路径。
        if (callbackContext.invocationContext().session() != null
                && callbackContext.invocationContext().session().state() != null) {
            // 按约定的键取出链路标识。
            traceId = callbackContext.invocationContext().session().state().get(TraceContext.TRACE_ID_STATE_KEY);
        }

        // 会话状态里没有，再从回调数据里找一次。
        if (traceId == null && callbackContext.invocationContext().callbackContextData() != null) {
            // 同样按约定的键取。
            traceId = callbackContext.invocationContext().callbackContextData().get(TraceContext.TRACE_ID_STATE_KEY);
        }

        // 两处都没有就返回空。
        return traceId == null ? null : String.valueOf(traceId);
    }

    /**
     * 把一次模型调用的用量写进数据库，尽最大努力但绝不因此让模型调用失败。
     *
     * <p>各层职责：
     * 第一层：取出 ADK 可信状态，所有身份字段都从这里读。
     * 第二层：确定终止原因——显式传入的优先，否则从响应里取。
     * 第三层：拼出用量记录：身份、调用编号、模型信息、状态、五类 Token、链路标识。
     * 第四层：写库失败只告警，不向外抛。</p>
     *
     * <p>数据流：
     * 回调上下文 + 响应 + 模型版本 + 用量 + 状态
     * → 从可信 state 取身份（租户/用户/会话/运行）
     * → 取 callId 与 invocationId
     * → 组装用量实体（含五类 Token）
     * → 落库（按 callId 幂等，可被多次调用覆盖状态）
     * → 失败仅记 warn</p>
     *
     * <p>为什么必须吞掉异常：用量是旁路观测。因为一条统计写不进去就让用户的对话失败，
     * 是完全不成比例的代价。</p>
     *
     * <p>身份取值优先可信 state，只在缺失时才退回 ADK 自带值，避免用量被记到错误的用户名下。</p>
     */
    private void recordUsage(CallbackContext context, LlmResponse response, String modelVersion,
                             GenerateContentResponseUsageMetadata usage, String status, String finishReason) {
        // 写库可能失败，整段包起来保证不影响模型调用。
        try {
            // 第一层：取 ADK 可信状态表。
            Map<String, Object> state = context.state();
            // 第二层：显式传入的终止原因优先。
            String reason = finishReason;
            // 没有显式原因时，尝试从响应里取。
            if (reason == null && response != null) {
                // 从响应的 finishReason 转成字符串。
                reason = response.finishReason().map(Object::toString).orElse(null);
            }
            // 第三层：身份取可信 state，缺失用户时才回退 ADK callback 用户。
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
            // 第四层：用量是旁路观测，持久化失败只告警。
            log.warn("模型用量落库失败 invocationId:{}", context.invocationId(), e);
        }
    }

    /**
     * 算出「调用作用域」的键：同一次 invocation 里的同一个 Agent。
     *
     * <p>为什么要带上 Agent 名：组合工作流里一次 invocation 会依次调用多个子 Agent，
     * 只用 invocationId 会让它们的 callId 队列和计时数据互相覆盖。</p>
     */
    private String callScope(CallbackContext context) {
        // invocationId 与 Agent 名共同隔离嵌套调用。
        return context.invocationId() + ":" + context.agentName();
    }

    /**
     * 取出当前作用域队首的调用编号，缺失时补建一个。
     *
     * <p>为什么要补建：正常流程里 before 回调已经入队了。但如果生命周期出现异常（比如只触发了
     * 错误回调），队列会是空的。这时补一个新编号，保证用量至少能落库，而不是因为没有 callId 就丢掉记录。</p>
     *
     * <p>数据流：作用域键 → 取（或建）队列 → 看队首 → 有则返回；没有则新建编号入队并返回。</p>
     */
    private String currentCallId(CallbackContext context) {
        // 取出（或初始化）这个作用域的调用编号队列。
        ConcurrentLinkedDeque<String> calls = activeCallIds.computeIfAbsent(callScope(context),
                key -> new ConcurrentLinkedDeque<>());
        // 看队首，它就是当前进行中的那次调用。
        String callId = calls.peekFirst();
        // 正常情况下能取到，直接返回。
        if (callId != null) {
            // 返回队首编号。
            return callId;
        }
        // 队列为空说明生命周期异常，补建一个编号保证用量仍可落库。
        callId = "call_" + UUID.randomUUID();
        // 入队，后续回调也能配对到它。
        calls.addLast(callId);
        // 返回补建的编号。
        return callId;
    }

    /**
     * 结束当前作用域队首的那次调用，队列空了就把作用域整个移除。
     *
     * <p>必须移除空作用域：否则每次对话都会往这两张表里留下一个永不清理的条目，
     * 长时间运行会持续吃内存。</p>
     *
     * <p>用带值的 remove 是为了避免误删——如果期间有别的线程放进了新队列，就不该删。</p>
     */
    private void finishCall(CallbackContext context) {
        // 算出作用域键。
        String scope = callScope(context);
        // 取出队列；不存在说明已经被清理过。
        ConcurrentLinkedDeque<String> calls = activeCallIds.get(scope);
        // 没有队列就无需处理。
        if (calls == null) {
            // 直接返回。
            return;
        }
        // 出队当前这次调用。
        calls.pollFirst();
        // 队列空了才移除作用域，避免留下永久条目。
        if (calls.isEmpty()) {
            // 带值移除，防止误删别的线程刚放入的新队列。
            activeCallIds.remove(scope, calls);
        }
    }

    /**
     * 把 state 里的任意值安全地转成字符串。
     *
     * <p>空值保持为空，避免把 "null" 当成有效的租户或用户编号写进用量表。</p>
     */
    private String value(Object value) {
        // 空值保持为空。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 主值为空时退回备用值。
     *
     * <p>用在用户、Agent、模型版本这些字段上，保证用量记录的关键维度不为空——
     * 空维度会让后续按租户或模型汇总时漏掉这条记录。</p>
     */
    private String defaultValue(String value, String fallback) {
        // 空值和纯空白都算缺失，用备用值补上。
        return value == null || value.isBlank() ? fallback : value;
    }
}
