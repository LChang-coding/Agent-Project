package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolHandler;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import cn.bugstack.ai.domain.run.service.RunControlService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型可见的 {@code rag_retrieve} 平台工具处理器。
 * <p>模型只能提供检索问题和 Token 预算；租户、用户、运行目标与可用绑定
 * 全部来自服务端工具上下文。处理器负责预算预留、同步检索、取消状态复查与证据登记。</p>
 */
@Service
public class RagRetrievePlatformToolHandler implements PlatformToolHandler {

    /** 平台工具目录中的固定函数名。 */
    private static final String FUNCTION_NAME = "rag_retrieve";
    /** 单次模型调用允许请求的最小上下文 Token 数。 */
    private static final int MIN_CONTEXT_TOKENS = 128;
    /** 单次模型调用允许请求的最大上下文 Token 数。 */
    private static final int MAX_CONTEXT_TOKENS = 8_000;
    /** 模型提供检索问题的最大字符数。 */
    private static final int MAX_QUERY_LENGTH = 2_000;
    /** 模型唯一允许提供的工具参数。 */
    private static final Set<String> MODEL_ARGUMENTS = Set.of("query", "maxContextTokens");

    /** 执行服务端授权、召回、融合与引用组装。 */
    private final RagRetrievalService retrievalService;
    /** 将检索结果转换为模型参考上下文。 */
    private final RagRetrievalPresentationService presentationService;
    /** 在运行范围内登记工具调用产生的引用证据。 */
    private final RagInvocationEvidenceStore evidenceStore;
    /** 限制单次运行的检索次数与累计 Token 用量。 */
    private final RagToolInvocationBudgetStore budgetStore;
    /** 在证据登记前复查运行是否仍可执行。 */
    private final RunControlService runControlService;

    /**
     * 创建不含运行取消复查的处理器，仅用于兼容历史装配和测试。
     * @param registry 平台工具处理器注册表
     * @param retrievalService RAG 检索服务
     * @param presentationService 检索结果展示转换服务
     * @param evidenceStore 运行级引用证据存储
     * @param budgetStore 运行级工具调用预算存储
     */
    public RagRetrievePlatformToolHandler(PlatformToolRegistry registry,
                                          RagRetrievalService retrievalService,
                                          RagRetrievalPresentationService presentationService,
                                          RagInvocationEvidenceStore evidenceStore,
                                          RagToolInvocationBudgetStore budgetStore) {
        this(registry, retrievalService, presentationService, evidenceStore, budgetStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    /**
     * 创建处理器并把 {@code rag_retrieve} 登记到平台工具注册表。
     * @param registry 平台工具处理器注册表
     * @param retrievalService RAG 检索服务
     * @param presentationService 检索结果展示转换服务
     * @param evidenceStore 运行级引用证据存储
     * @param budgetStore 运行级工具调用预算存储
     * @param runControlService 用于证据登记前复查取消与运行状态的服务
     */
    public RagRetrievePlatformToolHandler(PlatformToolRegistry registry,
                                          RagRetrievalService retrievalService,
                                          RagRetrievalPresentationService presentationService,
                                          RagInvocationEvidenceStore evidenceStore,
                                          RagToolInvocationBudgetStore budgetStore,
                                          RunControlService runControlService) {
        this.retrievalService = retrievalService;
        this.presentationService = presentationService;
        this.evidenceStore = evidenceStore;
        this.budgetStore = budgetStore;
        this.runControlService = runControlService;
        registry.register(FUNCTION_NAME, this);
    }

    /**
     * 使用服务端可信上下文执行一次有预算的 RAG 检索。
     * <p>只有检索结果未超额、运行仍可执行且证据已登记后才结算预算；
     * 参数错误和运行时失败会回滚本次预留。</p>
     *
     * @param tool 工具目录中的 {@code rag_retrieve} 定义
     * @param input 模型提供的 query 与 maxContextTokens
     * @param context 包含可信身份、运行目标和冻结绑定的工具上下文
     * @return 可供模型使用的参考上下文与有界审计摘要，或稳定失败码
     */
    @Override
    public PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input,
                                     ToolInvokeContextEntity context) {
        RagToolInvocationBudgetStore.Reservation reservation = null;
        try {
            // 模型只能提供问题和期望文本量；租户、运行和知识库范围全部取自服务端上下文。
            validateModelArguments(input);
            String query = modelQuery(input);
            int maxContextTokens = modelTokenBudget(input);
            TrustedContext trusted = trusted(context);
            // 先占用一次调用次数和最大 Token，防止并发工具调用同时越过本次运行的总额度。
            reservation = budgetStore.reserve(trusted.tenantId, trusted.userId, trusted.runId, maxContextTokens);
            RagRetrievalResult retrieved = retrievalService.retrieve(new RagRetrievalRequest(trusted.tenantId,
                    trusted.userId, trusted.sessionId, trusted.runId, trusted.targetType, trusted.targetId,
                    query, trusted.traceId, maxContextTokens, false, trusted.bindingIds));
            RagRetrievalPresentationService.Presentation presentation = presentationService.present(retrieved);
            if (retrieved.estimatedTokenCount() > maxContextTokens) {
                throw new IllegalArgumentException("检索结果Token超过预留预算");
            }
            // 检索期间用户可能取消运行；写入证据前必须重新确认这次回答仍然有效。
            if (runControlService != null) {
                runControlService.requireExecutable(trusted.tenantId, trusted.userId, trusted.runId, null);
            }
            // 先登记这次真正返回给 Agent 的引用，再把预留额度结算为实际使用量。
            evidenceStore.record(trusted.tenantId, trusted.userId, trusted.sessionId, trusted.runId,
                    trusted.evidenceInvocationId, List.of(presentation.evidence()));
            reservation.complete(retrieved.estimatedTokenCount());

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("retrievalId", retrieved.retrievalId());
            model.put("query", query);
            model.put("context", presentation.content());
            model.put("citations", retrieved.citations().stream().map(this::citationSummary).toList());
            model.put("stats", stats(retrieved));

            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("retrievalId", retrieved.retrievalId());
            audit.put("hits", retrieved.citations().size());
            audit.put("estimatedTokenCount", retrieved.estimatedTokenCount());
            audit.put("degraded", retrieved.degraded());
            return new PlatformToolResult(true, Map.copyOf(model), Map.copyOf(audit), null);
        } catch (RagToolInvocationBudgetStore.BudgetExceededException exception) {
            return PlatformToolResult.failure("RAG_TOOL_BUDGET_EXCEEDED:" + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            // 参数或结果边界失败没有产生可用证据，归还本次预留，允许 Agent 修正参数后再调用。
            if (reservation != null) reservation.rollback();
            return PlatformToolResult.failure("RAG_TOOL_INVALID_REQUEST:" + exception.getMessage());
        } catch (RuntimeException exception) {
            // 外部检索、取消检查或证据登记失败时同样归还额度，避免一次失败永久占用预算。
            if (reservation != null) reservation.rollback();
            return PlatformToolResult.failure("RAG_TOOL_RETRIEVAL_FAILED");
        }
    }

    /** 禁止模型提供身份、目标、绑定或其他未定义参数。 */
    private void validateModelArguments(Map<String, Object> input) {
        if (input == null) throw new IllegalArgumentException("工具参数不能为空");
        if (!MODEL_ARGUMENTS.containsAll(input.keySet())) {
            throw new IllegalArgumentException("只允许query和maxContextTokens参数");
        }
    }

    /** 读取并校验模型提供的非空检索问题与长度上限。 */
    private String modelQuery(Map<String, Object> input) {
        Object value = input.get("query");
        if (!(value instanceof String query) || query.isBlank()) {
            throw new IllegalArgumentException("query不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("query长度不能超过2000");
        return query;
    }

    /** 读取 128 到 8000 之间的整数 Token 预算，未提供时使用 8000。 */
    private int modelTokenBudget(Map<String, Object> input) {
        Object value = input.get("maxContextTokens");
        if (value == null) return MAX_CONTEXT_TOKENS;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("maxContextTokens必须为整数");
        long tokens = number.longValue();
        if (tokens < MIN_CONTEXT_TOKENS || tokens > MAX_CONTEXT_TOKENS || number.doubleValue() != tokens) {
            throw new IllegalArgumentException("maxContextTokens必须为128到8000的整数");
        }
        return (int) tokens;
    }

    /** 仅向模型返回引用标识与文档展示信息，不暴露内部存储位置。 */
    private Map<String, Object> citationSummary(RagRetrievalResult.Citation citation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationId", citation.citationId());
        value.put("documentId", citation.documentId());
        value.put("title", citation.documentName());
        return Map.copyOf(value);
    }

    /** 生成命中数、Token、耗时和降级原因的有界统计。 */
    private Map<String, Object> stats(RagRetrievalResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hits", result.citations().size());
        value.put("citations", result.citations().size());
        value.put("tokens", result.estimatedTokenCount());
        value.put("costMs", result.metrics().totalMs());
        value.put("degraded", result.degraded());
        value.put("degradationReasons", result.degradationReasons());
        return Map.copyOf(value);
    }

    /** 从服务端工具上下文提取并冻结 RAG 检索所需的可信运行信息。 */
    private TrustedContext trusted(ToolInvokeContextEntity context) {
        if (context == null) throw new IllegalArgumentException("可信工具上下文不能为空");
        return new TrustedContext(requireText(context.getTenantId(), "租户ID"),
                requireText(context.getUserId(), "用户ID"), requireText(context.getSessionId(), "会话ID"),
                requireText(context.getRunId(), "运行ID"), parseTargetType(context.getRagTargetType()),
                requireText(context.getRagTargetId(), "RAG目标ID"), requireText(context.getTraceId(), "Trace ID"),
                context.getRagBindingIds() == null ? List.of() : List.copyOf(context.getRagBindingIds()),
                requireText(context.getRagEvidenceInvocationId(), "RAG证据调用ID"));
    }

    /** 将服务端冻结的目标类型解析为受限枚举。 */
    private RagBindingTargetType parseTargetType(String value) {
        try {
            return RagBindingTargetType.valueOf(requireText(value, "RAG目标类型"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("RAG目标类型非法");
        }
    }

    /** 校验可信上下文必需的文本字段。 */
    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    /** 已从服务端上下文校验并冻结的 RAG 工具执行信息。 */
    private record TrustedContext(String tenantId, String userId, String sessionId, String runId,
                                  RagBindingTargetType targetType, String targetId, String traceId,
                                  List<String> bindingIds, String evidenceInvocationId) {
    }
}
