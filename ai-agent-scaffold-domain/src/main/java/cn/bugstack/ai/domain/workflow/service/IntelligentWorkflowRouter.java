package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对节点结果执行确定性路由裁决；模型只能给建议，不能直接调度下一节点。 */
@Service
public class IntelligentWorkflowRouter {

    private static final Pattern EQUALS = Pattern.compile("^(status|output|suggestion)\\s*==\\s*'([^']{0,256})'$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTAINS = Pattern.compile("^(output|suggestion)\\s+contains\\s+'([^']{1,256})'$", Pattern.CASE_INSENSITIVE);
    private static final List<String> STRATEGY_ORDER = List.of(
            "FAILURE", "FIXED", "SUCCESS", "EXPRESSION", "NODE_SUGGESTION", "AI_ROUTER", "DEFAULT");

    /** 依据固定平台优先级选择唯一下一跳；取消和预算门禁永远先于业务策略。 */
    public RouteDecision decide(RouteContext context,
                                WorkflowDagPlanEntity.Node node,
                                List<WorkflowDagPlanEntity.Edge> candidateEdges) {
        if (context == null || node == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "智能路由上下文和节点不能为空");
        }
        if (context.cancelled()) {
            return terminal("CANCEL_GUARD", "END", "运行已取消");
        }
        if (context.executedSteps() >= context.maxSteps() || context.usedTokens() >= context.tokenBudget()) {
            return terminal("BUDGET_GUARD", "END", "运行预算已耗尽");
        }
        List<WorkflowDagPlanEntity.Edge> edges = ordered(candidateEdges);
        Set<String> enabled = normalizedStrategies(node.getEnabledStrategies());
        for (String strategy : STRATEGY_ORDER) {
            if (!enabled.isEmpty() && !enabled.contains(strategy) && !"FAILURE".equals(strategy) && !"DEFAULT".equals(strategy)) {
                continue;
            }
            for (WorkflowDagPlanEntity.Edge edge : edges) {
                if (!strategy.equals(type(edge))) {
                    continue;
                }
                if (matches(strategy, edge, context)) {
                    assertAllowed(node, edge.getTargetNodeId());
                    return new RouteDecision(strategy, edge.getTargetNodeId(), edge.getEdgeId(), "策略命中", false);
                }
            }
        }
        throw new AppException("WORKFLOW_ROUTE_NOT_FOUND", "节点没有可用路由: " + node.getNodeId());
    }

    /** 编译器和运行时共用同一表达式白名单，避免校验与执行语义漂移。 */
    public static boolean supportedExpression(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 320) {
            return false;
        }
        return EQUALS.matcher(expression.trim()).matches() || CONTAINS.matcher(expression.trim()).matches();
    }

    private boolean matches(String strategy, WorkflowDagPlanEntity.Edge edge, RouteContext context) {
        return switch (strategy) {
            case "FAILURE" -> context.failed();
            case "FIXED" -> true;
            case "SUCCESS" -> !context.failed();
            case "EXPRESSION" -> expressionMatches(edge.getConditionExpression(), context);
            case "NODE_SUGGESTION" -> same(edge.getRouteKey(), context.nodeSuggestion());
            case "AI_ROUTER" -> same(edge.getRouteKey(), context.aiRouteKey());
            case "DEFAULT" -> true;
            default -> false;
        };
    }

    private boolean expressionMatches(String expression, RouteContext context) {
        if (!supportedExpression(expression)) {
            return false;
        }
        String normalized = expression.trim();
        Matcher equals = EQUALS.matcher(normalized);
        if (equals.matches()) {
            return same(value(equals.group(1), context), equals.group(2));
        }
        Matcher contains = CONTAINS.matcher(normalized);
        return contains.matches() && value(contains.group(1), context).contains(contains.group(2));
    }

    private String value(String name, RouteContext context) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "status" -> context.failed() ? "FAILED" : "SUCCEEDED";
            case "suggestion" -> safe(context.nodeSuggestion());
            default -> safe(context.output());
        };
    }

    private void assertAllowed(WorkflowDagPlanEntity.Node node, String targetNodeId) {
        List<String> allowed = node.getAllowedTargetNodeIds();
        if (allowed != null && !allowed.isEmpty() && allowed.stream().noneMatch(target -> same(target, targetNodeId))) {
            throw new AppException("WORKFLOW_ROUTE_TARGET_DENIED", "节点不允许路由到目标: " + targetNodeId);
        }
    }

    private List<WorkflowDagPlanEntity.Edge> ordered(List<WorkflowDagPlanEntity.Edge> candidateEdges) {
        List<WorkflowDagPlanEntity.Edge> edges = new ArrayList<>(candidateEdges == null ? List.of() : candidateEdges);
        edges.sort(Comparator.comparingInt(edge -> edge.getPriority() == null ? 1000 : edge.getPriority()));
        return edges;
    }

    private Set<String> normalizedStrategies(List<String> strategies) {
        Set<String> result = new LinkedHashSet<>();
        if (strategies != null) {
            strategies.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toUpperCase(Locale.ROOT)).forEach(result::add);
        }
        return result;
    }

    private String type(WorkflowDagPlanEntity.Edge edge) {
        return edge == null || edge.getRouteType() == null || edge.getRouteType().isBlank()
                ? "FIXED" : edge.getRouteType().toUpperCase(Locale.ROOT);
    }

    private RouteDecision terminal(String strategy, String target, String reason) {
        return new RouteDecision(strategy, target, null, reason, true);
    }

    private boolean same(String left, String right) {
        return safe(left).equalsIgnoreCase(safe(right));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 路由只读取已落地节点结果和预算快照，不接触可变 Controller DTO。 */
    public record RouteContext(boolean cancelled, boolean failed, String output, String nodeSuggestion,
                               String aiRouteKey, int executedSteps, int maxSteps, long usedTokens,
                               long tokenBudget) {
    }

    /** 唯一裁决结果；terminal 表示运行必须收口而不是继续调度。 */
    public record RouteDecision(String strategy, String targetNodeId, String edgeId, String reason,
                                boolean terminal) {
    }
}
