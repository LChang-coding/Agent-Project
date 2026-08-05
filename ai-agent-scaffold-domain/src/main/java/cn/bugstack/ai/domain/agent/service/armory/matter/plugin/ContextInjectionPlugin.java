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
 * 每次模型调用之前，把这轮该让模型看到的东西装进请求：历史消息、压缩摘要、附件、上游输出和知识库检索结果。
 *
 * <p>解决什么问题：ADK 自己的会话内存只服务单次调用，装不了跨轮历史，也不知道知识库。
 * 真正的上下文来自数据库和检索服务，必须在调模型的最后一刻装进去——太早会用到过期数据，
 * 太晚就来不及了。</p>
 *
 * <p>所属层次：领域层的装配辅料（ADK 插件）。</p>
 *
 * <p>谁会调用它：ADK 在每次调用模型前回调它；{@code RunnerNode} 会强制把它挂进每个 Runner。</p>
 *
 * <p>它向下调用什么：{@code ConversationMemoryService} 组装上下文（它决定历史条数、压缩摘要、
 * 附件和检索结果各占多少预算），{@code RagInvocationEvidenceStore} 暂存这次真实注入的检索证据。</p>
 *
 * <p>它不负责什么：不决定上下文预算怎么分（那是上下文服务的事）、不写业务消息、不调用模型。</p>
 *
 * <p>关键设计：注入的内容作为「系统指令」追加，不和用户原始消息混写。
 * 混写会让模型分不清哪句是用户说的、哪句是系统给的资料，也会污染落库的消息内容。</p>
 */
@Slf4j
@Service("contextInjectionPlugin")
public class ContextInjectionPlugin extends BasePlugin {

    /**
     * 上下文组装服务：按运行快照和可见序号决定这轮模型该看到什么。
     *
     * <p>它负责裁剪历史、取压缩摘要、拉附件内容、发起知识库检索，并保证总量不超过模型的输入上限。</p>
     */
    private final ConversationMemoryService conversationMemoryService;
    /**
     * 检索证据暂存仓：记下这次模型调用真实注入了哪些知识库片段。
     *
     * <p>为什么必须记：回答生成后要校验模型引用的出处是否真的检索到过。
     * 没有这份记录，模型编造引用就无法被发现。</p>
     */
    private final RagInvocationEvidenceStore evidenceStore;

    /**
     * 注入上下文服务和证据仓，并固定插件名。
     *
     * <p>名字写死是必要的：{@code RunnerNode} 靠它判断是否已挂载，
     * 重复挂载会导致上下文被注入两遍，白白消耗一倍的输入 Token。</p>
     */
    public ContextInjectionPlugin(ConversationMemoryService conversationMemoryService,
                                  RagInvocationEvidenceStore evidenceStore) {
        // 用固定插件名注册，供 Runner 去重识别。
        super("contextInjectionPlugin");
        // 保存上下文组装服务。
        this.conversationMemoryService = conversationMemoryService;
        // 保存检索证据暂存仓。
        this.evidenceStore = evidenceStore;
    }

    /**
     * 在模型调用前组装并注入上下文。
     *
     * <p>各层职责：
     * 第一层：从 ADK 的可信 state 里读出全部身份和切面参数——这些都是 ChatService 注入的，
     *         绝不从模型输出里取，否则模型能自己指定读谁的历史。
     * 第二层：把参数交给上下文服务组装，由它统一决定历史、摘要、附件、上游输出和检索结果的配比。
     * 第三层：组装出的指令作为系统指令追加进请求，不与用户原始消息混写。
     * 第四层：把这次真实注入的检索证据记进暂存仓，供回答生成后校验引用是否真实。
     * 第五层：无论成功还是失败都打一条结构化日志，便于统计上下文装配耗时和证据数量。
     * 第六层：区分两类失败——必需知识库不可用或越权检索必须中断（失败关闭），
     *         其余上下文故障允许模型在没有注入的情况下继续回答（失败开放）。</p>
     *
     * <p>数据流：
     * ADK 可信 state（租户/用户/会话/运行/链路/可见序号/RAG 参数）
     * → 上下文服务组装
     * → 得到系统指令 + 检索证据
     * → 指令追加进模型请求
     * → 证据写入暂存仓
     * → 打点记录耗时与证据数
     * → 返回空（不短路模型）
     * → 模型带着完整上下文开始生成</p>
     *
     * <p>返回空表示不短路，让模型正常执行；这个插件永远不会替模型给出回答。</p>
     *
     * <p>为什么「必需 RAG 不可用」要失败关闭：用户明确配置了必须基于知识库回答，
     * 检索不到却照常回答，模型就会凭记忆瞎编，比直接报错危险得多。</p>
     */
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        // 记下开始时间，用于统计上下文装配耗时。
        long startedAt = System.currentTimeMillis();
        // 第一层：所有身份和切面均来自 ChatService 注入的可信 state。
        Map<String, Object> state = callbackContext.state();
        // 租户编号，上下文查询的隔离维度，错了就会读到别人的历史。
        String tenantId = stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID));
        // 用户编号，优先用注入的可信值，缺失时退回 ADK 自带值。
        String userId = defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), callbackContext.userId());
        // 会话编号，决定读哪一段对话历史。
        String sessionId = stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID));
        // 运行编号，检索证据要挂在它下面。
        String runId = stringValue(state.get(ToolRuntimeContextKeys.RUN_ID));
        // 链路标识，用于把上下文装配日志串进整条请求链路。
        String traceId = stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID));
        // 有 RAG 目标类型就说明本次运行启用了知识库；这个开关在运行创建时就固化了，中途改会话设置不影响。
        boolean ragEnabled = state.get(ToolRuntimeContextKeys.RAG_TARGET_TYPE) != null;
        // 打一条开始日志，和下面的完成/失败日志配对，便于统计成功率和耗时。
        AiLog.info(AiLog.chat().contextStarted(tenantId, userId, sessionId, runId, ragEnabled)
                .field(AiLogFields.TRACE_ID, traceId));
        // 组装过程涉及查库和检索，随时可能失败；这里必须按两类策略分别处理。
        try {
            // 第二层：Context Manager 统一决定历史、压缩摘要、附件、上游输出和 RAG 的预算。
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
            // 第三层：组装出内容才注入；空指令说明这轮没有历史也没有检索结果。
            if (result.getInstruction() != null && !result.getInstruction().isBlank()) {
                // 作为系统指令追加，不与原始用户 Content 混写。
                llmRequest.appendInstructions(List.of(result.getInstruction()));
            }
            // 第四层：本次确实注入了检索片段，必须记下来供回答引用校验使用。
            if (result.getRagEvidence() != null && !result.getRagEvidence().isEmpty()) {
                // 工作流节点使用显式 evidenceInvocationId，普通 Agent 使用 ADK invocationId；
                // 两者都要能把证据精确绑定到「这一次模型调用」，否则多节点之间的证据会串。
                evidenceStore.record(tenantId, userId, sessionId, runId,
                        defaultString(stringValue(state.get(ToolRuntimeContextKeys.RAG_EVIDENCE_INVOCATION_ID)),
                                callbackContext.invocationId()),
                        result.getRagEvidence());
            }
            // 第五层：打完成日志，带上估算 Token 数、证据条数和耗时，便于监控上下文膨胀。
            AiLog.info(AiLog.chat().contextCompleted(tenantId, userId, sessionId, runId, ragEnabled,
                    result.getEstimatedTokenCount(),
                    result.getRagEvidence() == null ? 0 : result.getRagEvidence().size(),
                    System.currentTimeMillis() - startedAt).field(AiLogFields.TRACE_ID, traceId));
            // 返回空表示不短路，模型带着刚注入的上下文继续执行。
            return Maybe.empty();
        } catch (Exception e) {
            // 先记失败日志，保留耗时便于判断是超时还是立即失败。
            AiLog.error(AiLog.chat().contextFailed(tenantId, userId, sessionId, runId, ragEnabled,
                    System.currentTimeMillis() - startedAt, e).field(AiLogFields.TRACE_ID, traceId));
            // 第六层：判断这类失败是否必须中断整次调用。
            if (mustFailClosed(e)) {
                // 必需知识库不可用或检索越权时禁止无 RAG 继续回答，否则模型会凭记忆编造。
                throw (RuntimeException) e;
            }
            // 其余故障允许降级：没有上下文的回答质量差，但总比直接报错好。
            log.warn("上下文注入失败 traceId:{} invocationId:{} sessionId:{}",
                    extractTraceId(callbackContext), callbackContext.invocationId(), callbackContext.sessionId(), e);
            // 返回空放模型继续跑，只是这轮没有历史和检索结果。
            return Maybe.empty();
        }
    }

    /**
     * 判断一个上下文装配失败是否必须中断整次模型调用。
     *
     * <p>只有两类必须中断：一是配置了必需知识库但检索不可用（放行会导致模型凭记忆编造），
     * 二是检索范围越权（放行等于把不该看的资料递给了模型）。其余故障一律降级放行。</p>
     *
     * <p>按错误码前缀和关键字匹配，非业务异常直接判为可降级。</p>
     */
    private boolean mustFailClosed(Exception exception) {
        // 不是带错误码的业务异常，一律按可降级处理。
        if (!(exception instanceof AppException appException) || appException.getCode() == null) {
            // 返回 false 表示允许模型继续跑。
            return false;
        }
        // 必需 RAG 类错误和范围越权类错误必须中断，其余降级。
        return appException.getCode().startsWith("RAG_REQUIRED_")
                || appException.getCode().contains("SCOPE_VIOLATION");
    }

    /**
     * 把 state 里的 RAG 目标类型字符串安全地转成枚举。
     *
     * <p>做了去空白和大写归一，兼容配置和序列化过程中的大小写差异。</p>
     *
     * <p>无法识别时返回空而不是抛异常：结果是这轮不启用检索，模型仍能基于历史回答，
     * 比让整次对话失败更合理。</p>
     */
    private RagBindingTargetType enumValue(Object value) {
        // 没有值就表示不启用 RAG。
        if (value == null) return null;
        // 转换可能因为取值不在枚举里而失败，必须接住。
        try {
            // 去空白并转大写后匹配枚举，兼容大小写写法差异。
            return RagBindingTargetType.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            // 无法识别就当作不启用 RAG，不中断对话。
            return null;
        }
    }

    /**
     * 取出这次调用的链路标识，优先用 state 里注入的值。
     *
     * <p>为什么不直接用线程上下文：模型回调可能在别的线程上执行，线程上下文里的链路标识
     * 可能已经是别的请求的了。state 里的值跟着调用走，更可靠。</p>
     */
    private String extractTraceId(CallbackContext callbackContext) {
        // 没有上下文或状态表时只能退回线程里的链路标识。
        if (callbackContext == null || callbackContext.state() == null) {
            // 返回线程当前的链路标识。
            return TraceContext.getTraceId();
        }
        // 优先用 state 里注入的值，缺失才退回线程值。
        return defaultString(stringValue(callbackContext.state().get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.getTraceId());
    }

    /**
     * 把 state 里的任意值安全地转成字符串。
     *
     * <p>空值保持为空，不要变成 "null" 这种会被误认为有效值的字符串。</p>
     */
    private String stringValue(Object value) {
        // 空值保持为空。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把 state 里的可见消息序号解析成整型。
     *
     * <p>这个序号决定模型能看到哪些历史消息，解析错会导致上下文范围偏移——
     * 要么少给历史让模型失忆，要么多给历史把本轮输入重复喂进去。</p>
     *
     * <p>非法值返回空，交给上下文服务按缺失处理，而不是擅自当成 0。</p>
     */
    private Integer integerValue(Object value) {
        // 没有值就按缺失处理。
        if (value == null) {
            // 返回空。
            return null;
        }
        // 已经是数字就直接转。
        if (value instanceof Number number) {
            // 统一转成整型。
            return number.intValue();
        }
        // 否则按字符串解析，兼容序列化往返后的类型变化。
        try {
            // 解析成整型。
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            // 非法值按缺失处理，不擅自取默认值。
            return null;
        }
    }

    /**
     * 把 state 里的知识库绑定编号列表规范成干净的字符串列表。
     *
     * <p>拒绝非列表输入：类型不对说明注入方写错了键，此时返回空列表相当于「不限定绑定范围」，
     * 由上下文服务按默认策略处理，而不是把一个错误结构传下去。</p>
     *
     * <p>顺带过滤掉空元素和空白字符串，避免拿一个空 ID 去查库。</p>
     */
    private List<String> stringList(Object value) {
        // 不是列表就返回空列表，交给下游按默认策略处理。
        if (!(value instanceof List<?> values)) {
            // 返回不可变空列表。
            return List.of();
        }
        // 过滤空元素，转成字符串后再过滤空白项，保证每个 ID 都能用于查询。
        return values.stream().filter(item -> item != null)
                .map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    /**
     * 主值为空时退回备用值。
     *
     * <p>用在用户编号、链路标识等字段上：优先用注入的可信值，缺失时退回 ADK 或线程提供的值，
     * 保证上下文组装至少有身份可用。</p>
     */
    private String defaultString(String value, String defaultValue) {
        // 空值和纯空白都算缺失，用备用值补上。
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
