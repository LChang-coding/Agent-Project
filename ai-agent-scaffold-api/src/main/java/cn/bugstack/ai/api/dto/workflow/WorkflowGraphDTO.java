package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

import java.util.List;

/**
 * 工作流画布图结构。
 */
@Data
public class WorkflowGraphDTO {

    /**
     * 编排模式：sequential/parallel/loop。
     */
    private String mode;

    /**
     * 根节点ID。
     */
    private String rootNodeId;

    /**
     * 节点列表。
     */
    private List<Node> nodes;

    /**
     * 连线列表。
     */
    private List<Edge> edges;

    /**
     * 画布节点。
     */
    @Data
    public static class Node {

        /**
         * 节点ID。
         */
        private String nodeId;

        /**
         * 节点类型：llm/sequential/parallel/loop。
         */
        private String nodeType;

        /**
         * 节点名称。
         */
        private String name;

        /**
         * 节点说明。
         */
        private String description;

        /**
         * 节点提示词。
         */
        private String instruction;

        /**
         * 节点模型编码。
         */
        private String modelCode;

        /**
         * 节点可使用的 MCP ID。
         */
        private List<String> mcpIds;

        /**
         * 节点可使用的 Skill ID。
         */
        private List<String> skillIds;

        /**
         * 循环节点最大迭代次数。
         */
        private Integer maxIterations;

        /**
         * 画布横向坐标。
         */
        private Integer x;

        /**
         * 画布纵向坐标。
         */
        private Integer y;
    }

    /**
     * 画布连线。
     */
    @Data
    public static class Edge {

        /**
         * 连线ID。
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
