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

    /** 历史定义缺失协议字段时继续使用正文 marker 路由。 */
    @Builder.Default
    private String routingProtocolVersion = "MARKER_V1";

    /** STATIC 沿用拓扑 DAG；INTELLIGENT 由每个节点完成后动态选择唯一下一跳。 */
    private String workflowKind;

    /** 单次智能运行允许调度的节点总数上限。 */
    private Integer maxSteps;

    /** 单次智能运行允许消耗的 Token 上限。 */
    private Long tokenBudget;

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

        /** 节点可启用的路由策略；运行时按平台固定优先级执行，数组顺序不改变优先级。 */
        private List<String> enabledStrategies;

        /** 节点允许到达的目标；END 表示显式结束。 */
        private List<String> allowedTargetNodeIds;

        /** 没有其他策略命中时的目标。 */
        private String defaultTargetNodeId;

        /** AI_ROUTER 使用的业务路由说明；不得包含密钥或系统提示词。 */
        private String routeInstruction;

        /** 整个运行中该节点允许被访问的次数。 */
        private Integer maxVisits;

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

        /** FIXED/SUCCESS/FAILURE/EXPRESSION/NODE_SUGGESTION/AI_ROUTER/DEFAULT。 */
        private String routeType;

        /** 节点建议或 AI 路由返回的稳定路由键。 */
        private String routeKey;

        /** 只匹配显式 route marker 的兼容键；不用于正文模糊推断。 */
        private List<String> routeAliases;

        /** 受限表达式；仅允许 status/output/suggestion 的比较和 contains。 */
        private String conditionExpression;

        /** 同一种策略内的数值优先级，数值越小越先匹配。 */
        private Integer priority;
    }
}
