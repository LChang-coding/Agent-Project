package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流 DAG 执行计划。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowDagPlanEntity {

    /** STATIC 或 INTELLIGENT；缺省按 STATIC 兼容旧版本。 */
    private String workflowKind;

    /** 智能工作流总步数硬上限。 */
    private Integer maxSteps;

    /** 智能工作流 Token 总预算。 */
    private Long tokenBudget;

    /**
     * 工作流ID。
     */
    private String workflowId;

    /**
     * 工作流版本。
     */
    private Integer version;

    /** 首个调度节点；运行时从这里展开可达边。 */
    private String rootNodeId;

    /**
     * 默认模型编码。
     */
    private String defaultModelCode;

    /**
     * 节点执行计划。
     */
    private List<Node> nodes;

    /**
     * 有向边计划。
     */
    private List<Edge> edges;

    /**
     * DAG 节点计划。
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Node {

        /**
         * 画布节点ID。
         */
        private String nodeId;

        /**
         * 节点展示名。
         */
        private String nodeName;

        /**
         * 节点说明。
         */
        private String description;

        /**
         * 实际注册到 Spring 容器的节点 Agent ID。
         */
        private String runtimeAgentId;

        /**
         * 节点 Agent 运行名。
         */
        private String runtimeAgentName;

        /**
         * 节点模型编码。
         */
        private String modelCode;

        /** 自循环节点的执行上限；防止无限迭代。 */
        private Integer maxIterations;

        private List<String> enabledStrategies;

        private List<String> allowedTargetNodeIds;

        private String defaultTargetNodeId;

        private String routeInstruction;

        private Integer maxVisits;
    }

    /**
     * DAG 有向边计划。
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Edge {

        /**
         * 边ID。
         */
        private String edgeId;

        /** 上游节点ID。 */
        private String sourceNodeId;

        /** 下游节点ID；与起点相同时表示有限自循环。 */
        private String targetNodeId;

        private String routeType;

        private String routeKey;

        private String conditionExpression;

        private Integer priority;
    }
}
