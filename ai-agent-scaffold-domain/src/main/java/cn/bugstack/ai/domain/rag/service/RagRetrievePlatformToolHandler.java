package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.PlatformToolHandler;
import cn.bugstack.ai.domain.tool.service.PlatformToolRegistry;
import cn.bugstack.ai.domain.tool.service.PlatformToolResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform handler for the model-visible {@code rag_retrieve} tool. */
@Service
public class RagRetrievePlatformToolHandler implements PlatformToolHandler {

    private static final String FUNCTION_NAME = "rag_retrieve";
    private static final int MIN_CONTEXT_TOKENS = 128;
    private static final int MAX_CONTEXT_TOKENS = 8_000;
    private static final int MAX_QUERY_LENGTH = 2_000;
    private static final Set<String> MODEL_ARGUMENTS = Set.of("query", "maxContextTokens");

    private final RagRetrievalService retrievalService;
    private final RagRetrievalPresentationService presentationService;
    private final RagInvocationEvidenceStore evidenceStore;
    private final RagToolInvocationBudgetStore budgetStore;

    public RagRetrievePlatformToolHandler(PlatformToolRegistry registry,
                                          RagRetrievalService retrievalService,
                                          RagRetrievalPresentationService presentationService,
                                          RagInvocationEvidenceStore evidenceStore,
                                          RagToolInvocationBudgetStore budgetStore) {
        this.retrievalService = retrievalService;
        this.presentationService = presentationService;
        this.evidenceStore = evidenceStore;
        this.budgetStore = budgetStore;
        registry.register(FUNCTION_NAME, this);
    }

    @Override
    public PlatformToolResult handle(ToolCatalogEntity tool, Map<String, Object> input,
                                     ToolInvokeContextEntity context) {
        RagToolInvocationBudgetStore.Reservation reservation = null;
        try {
            validateModelArguments(input);
            String query = modelQuery(input);
            int maxContextTokens = modelTokenBudget(input);
            TrustedContext trusted = trusted(context);
            reservation = budgetStore.reserve(trusted.tenantId, trusted.userId, trusted.runId, maxContextTokens);
            RagRetrievalResult retrieved = retrievalService.retrieve(new RagRetrievalRequest(trusted.tenantId,
                    trusted.userId, trusted.sessionId, trusted.runId, trusted.targetType, trusted.targetId,
                    query, trusted.traceId, maxContextTokens, false, trusted.bindingIds));
            RagRetrievalPresentationService.Presentation presentation = presentationService.present(retrieved);
            if (retrieved.estimatedTokenCount() > maxContextTokens) {
                throw new IllegalArgumentException("检索结果Token超过预留预算");
            }
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
            if (reservation != null) reservation.rollback();
            return PlatformToolResult.failure("RAG_TOOL_INVALID_REQUEST:" + exception.getMessage());
        } catch (RuntimeException exception) {
            if (reservation != null) reservation.rollback();
            return PlatformToolResult.failure("RAG_TOOL_RETRIEVAL_FAILED");
        }
    }

    private void validateModelArguments(Map<String, Object> input) {
        if (input == null) throw new IllegalArgumentException("工具参数不能为空");
        if (!MODEL_ARGUMENTS.containsAll(input.keySet())) {
            throw new IllegalArgumentException("只允许query和maxContextTokens参数");
        }
    }

    private String modelQuery(Map<String, Object> input) {
        Object value = input.get("query");
        if (!(value instanceof String query) || query.isBlank()) {
            throw new IllegalArgumentException("query不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("query长度不能超过2000");
        return query;
    }

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

    private Map<String, Object> citationSummary(RagRetrievalResult.Citation citation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationId", citation.citationId());
        value.put("documentId", citation.documentId());
        value.put("title", citation.documentName());
        return Map.copyOf(value);
    }

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

    private TrustedContext trusted(ToolInvokeContextEntity context) {
        if (context == null) throw new IllegalArgumentException("可信工具上下文不能为空");
        return new TrustedContext(requireText(context.getTenantId(), "租户ID"),
                requireText(context.getUserId(), "用户ID"), requireText(context.getSessionId(), "会话ID"),
                requireText(context.getRunId(), "运行ID"), parseTargetType(context.getRagTargetType()),
                requireText(context.getRagTargetId(), "RAG目标ID"), requireText(context.getTraceId(), "Trace ID"),
                context.getRagBindingIds() == null ? List.of() : List.copyOf(context.getRagBindingIds()),
                requireText(context.getRagEvidenceInvocationId(), "RAG证据调用ID"));
    }

    private RagBindingTargetType parseTargetType(String value) {
        try {
            return RagBindingTargetType.valueOf(requireText(value, "RAG目标类型"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("RAG目标类型非法");
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    private record TrustedContext(String tenantId, String userId, String sessionId, String runId,
                                  RagBindingTargetType targetType, String targetId, String traceId,
                                  List<String> bindingIds, String evidenceInvocationId) {
    }
}
