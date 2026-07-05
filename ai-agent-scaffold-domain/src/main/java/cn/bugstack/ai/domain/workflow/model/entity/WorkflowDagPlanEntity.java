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

    /**
     * 工作流ID。
     */
    private String workflowId;

    /**
     * 工作流版本。
     */
    private Integer version;

    /**
     * 根节点ID。
     */
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

        /**
         * 最大循环次数。
         */
        private Integer maxIterations;
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

        /**
         * 起点节点ID。
         */
        private String sourceNodeId;

        /**
         * 终点节点ID。
         */
        private String targetNodeId;
    }
}
