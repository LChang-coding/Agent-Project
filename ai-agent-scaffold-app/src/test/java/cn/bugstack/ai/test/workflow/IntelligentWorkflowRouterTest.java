package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRouter;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/** 智能工作流固定优先级与目标白名单测试。 */
public class IntelligentWorkflowRouterTest {

    private final IntelligentWorkflowRouter router = new IntelligentWorkflowRouter();

    @Test
    public void shouldStopBeforeBusinessRoutingWhenCancelled() {
        IntelligentWorkflowRouter.RouteDecision decision = router.decide(
                context(true, false, "通过", "RETRY", "APPROVE", 1, 20, 10, 100),
                node(List.of("node_b", "END")),
                List.of(edge("DEFAULT", "node_b", null, null, 100)));

        Assert.assertTrue(decision.terminal());
        Assert.assertEquals("CANCEL_GUARD", decision.strategy());
        Assert.assertEquals("END", decision.targetNodeId());
    }

    @Test
    public void shouldChooseExpressionBeforeSuggestionAndDefault() {
        IntelligentWorkflowRouter.RouteDecision decision = router.decide(
                context(false, false, "审核不通过，请退回", "APPROVE", "APPROVE", 1, 20, 10, 100),
                node(List.of("node_retry", "node_approve", "END")),
                List.of(
                        edge("DEFAULT", "END", null, null, 100),
                        edge("NODE_SUGGESTION", "node_approve", "APPROVE", null, 1),
                        edge("EXPRESSION", "node_retry", null, "output contains '不通过'", 20)));

        Assert.assertEquals("EXPRESSION", decision.strategy());
        Assert.assertEquals("node_retry", decision.targetNodeId());
    }

    @Test
    public void shouldRejectTargetOutsideNodeAllowList() {
        try {
            router.decide(context(false, false, "", null, null, 0, 20, 0, 100),
                    node(List.of("END")), List.of(edge("DEFAULT", "node_denied", null, null, 1)));
            Assert.fail("白名单外目标不应被调度");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_ROUTE_TARGET_DENIED", exception.getCode());
        }
    }

    @Test
    public void shouldAcceptOnlyWhitelistedExpressions() {
        Assert.assertTrue(IntelligentWorkflowRouter.supportedExpression("status == 'FAILED'"));
        Assert.assertTrue(IntelligentWorkflowRouter.supportedExpression("output contains '退回'"));
        Assert.assertFalse(IntelligentWorkflowRouter.supportedExpression("T(java.lang.Runtime).getRuntime()"));
        Assert.assertFalse(IntelligentWorkflowRouter.supportedExpression("output matches '.*'"));
    }

    private WorkflowDagPlanEntity.Node node(List<String> allowedTargets) {
        return WorkflowDagPlanEntity.Node.builder().nodeId("node_a")
                .enabledStrategies(List.of("EXPRESSION", "NODE_SUGGESTION", "DEFAULT"))
                .allowedTargetNodeIds(allowedTargets).build();
    }

    private WorkflowDagPlanEntity.Edge edge(String type, String target, String key, String expression, int priority) {
        return WorkflowDagPlanEntity.Edge.builder().edgeId("edge_" + type).sourceNodeId("node_a")
                .targetNodeId(target).routeType(type).routeKey(key).conditionExpression(expression)
                .priority(priority).build();
    }

    private IntelligentWorkflowRouter.RouteContext context(boolean cancelled, boolean failed, String output,
                                                             String suggestion, String aiRouteKey, int steps,
                                                             int maxSteps, long tokens, long tokenBudget) {
        return new IntelligentWorkflowRouter.RouteContext(cancelled, failed, output, suggestion, aiRouteKey,
                steps, maxSteps, tokens, tokenBudget);
    }
}
