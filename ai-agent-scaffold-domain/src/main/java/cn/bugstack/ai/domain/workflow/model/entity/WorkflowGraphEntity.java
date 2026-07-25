package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流画布图实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowGraphEntity {

    /** 旧式组合编排模式；DAG 执行以 edges 为准。 */
    private String mode;

    /** 显式入口节点；为空时编译器选择首个零入度节点。 */
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
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Node {

        /**
         * 节点ID。
         */
        private String nodeId;

        /** 节点类型；当前运行时只编译 LLM 节点。 */
        private String nodeType;

        /**
         * 节点名称。
         */
        private String name;

        /**
         * 节点描述。
         */
        private String description;

        /**
         * 节点提示词。
         */
        private String instruction;

        /**
         * 模型编码。
         */
        private String modelCode;

        /**
         * MCP ID 列表。
         */
        private List<String> mcpIds;

        /**
         * Skill ID 列表。
         */
        private List<String> skillIds;

        /** 自循环节点的执行上限。 */
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
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Edge {

        /**
         * 连线ID。
         */
        private String edgeId;

        /** 上游节点ID。 */
        private String sourceNodeId;

        /** 下游节点ID；与起点相同时表示自循环。 */
        private String targetNodeId;
    }
}
