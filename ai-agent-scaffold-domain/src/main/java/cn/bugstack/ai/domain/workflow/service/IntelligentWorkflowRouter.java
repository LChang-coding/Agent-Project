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

    /** 白名单等于表达式，只允许读取已落地的状态、输出或建议。 */
    private static final Pattern EQUALS = Pattern.compile("^(status|output|suggestion)\\s*==\\s*'([^']{0,256})'$", Pattern.CASE_INSENSITIVE);
    /** 白名单包含表达式，只允许读取输出或建议且限制比较文本长度。 */
    private static final Pattern CONTAINS = Pattern.compile("^(output|suggestion)\\s+contains\\s+'([^']{1,256})'$", Pattern.CASE_INSENSITIVE);
    /** 平台固定策略优先级，技术失败和显式业务边先于默认兜底。 */
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

    /** 按策略类型判断边是否命中，DEFAULT 只在更高优先级策略未命中后生效。 */
    private boolean matches(String strategy, WorkflowDagPlanEntity.Edge edge, RouteContext context) {
        return switch (strategy) {
            case "FAILURE" -> context.failed();
            case "FIXED" -> true;
            case "SUCCESS" -> !context.failed();
            case "EXPRESSION" -> expressionMatches(edge.getConditionExpression(), context);
            case "NODE_SUGGESTION" -> routeKeyMatches(edge, context.nodeSuggestion());
            case "AI_ROUTER" -> routeKeyMatches(edge, context.aiRouteKey());
            case "DEFAULT" -> true;
            default -> false;
        };
    }

    /** 只执行白名单中的等于或包含表达式，不解释任意代码。 */
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

    /** 从已落地节点结果中读取表达式允许访问的字段。 */
    private String value(String name, RouteContext context) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "status" -> context.failed() ? "FAILED" : "SUCCEEDED";
            case "suggestion" -> safe(context.nodeSuggestion());
            default -> safe(context.output());
        };
    }

    /** 校验目标属于节点冻结的允许集合，防止模型越过图定义。 */
    private void assertAllowed(WorkflowDagPlanEntity.Node node, String targetNodeId) {
        List<String> allowed = node.getAllowedTargetNodeIds();
        if (allowed != null && !allowed.isEmpty() && allowed.stream().noneMatch(target -> same(target, targetNodeId))) {
            throw new AppException("WORKFLOW_ROUTE_TARGET_DENIED", "节点不允许路由到目标: " + targetNodeId);
        }
    }

    /** 按业务优先级降序及边 ID 稳定排序，保证重复运行得到同一裁决。 */
    private List<WorkflowDagPlanEntity.Edge> ordered(List<WorkflowDagPlanEntity.Edge> candidateEdges) {
        List<WorkflowDagPlanEntity.Edge> edges = new ArrayList<>(candidateEdges == null ? List.of() : candidateEdges);
        edges.sort(Comparator.<WorkflowDagPlanEntity.Edge>comparingInt(
                        edge -> edge.getPriority() == null ? 0 : edge.getPriority()).reversed()
                .thenComparing(edge -> safe(edge.getEdgeId())));
        return edges;
    }

    /** 将节点启用策略归一为去重的大写集合。 */
    private Set<String> normalizedStrategies(List<String> strategies) {
        Set<String> result = new LinkedHashSet<>();
        if (strategies != null) {
            strategies.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toUpperCase(Locale.ROOT)).forEach(result::add);
        }
        return result;
    }

    /** 读取边策略类型，旧定义缺失时按固定边处理。 */
    private String type(WorkflowDagPlanEntity.Edge edge) {
        return edge == null || edge.getRouteType() == null || edge.getRouteType().isBlank()
                ? "FIXED" : edge.getRouteType().toUpperCase(Locale.ROOT);
    }

    /** 构造要求运行立即收口的终止裁决。 */
    private RouteDecision terminal(String strategy, String target, String reason) {
        return new RouteDecision(strategy, target, null, reason, true);
    }

    /** 对可选路由文本执行空值安全、忽略大小写的精确比较。 */
    private boolean same(String left, String right) {
        return safe(left).equalsIgnoreCase(safe(right));
    }

    /** 只对显式 marker 解析出的键做主键/别名精确匹配。 */
    private boolean routeKeyMatches(WorkflowDagPlanEntity.Edge edge, String candidate) {
        if (edge == null || !WorkflowRouteKey.valid(candidate)) return false;
        if (WorkflowRouteKey.same(edge.getRouteKey(), candidate)) return true;
        return edge.getRouteAliases() != null && edge.getRouteAliases().stream()
                .anyMatch(alias -> WorkflowRouteKey.same(alias, candidate));
    }

    /** 将可选表达式值归一为空串。 */
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
