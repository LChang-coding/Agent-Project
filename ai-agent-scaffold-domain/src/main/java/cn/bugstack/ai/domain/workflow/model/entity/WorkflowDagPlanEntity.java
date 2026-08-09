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

    /** 本次编译冻结的路由协议。 */
    private String routingProtocolVersion;

    /** 冻结定义的 SHA-256，用于工具意图与当前运行定义绑定。 */
    private String definitionHash;

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

        /** 冻结后的节点 MCP 白名单；空列表表示不允许外部 MCP。 */
        private List<String> mcpIds;

        /** 冻结后的节点 Skill 白名单；空列表表示当前节点不开放 Skill。 */
        private List<String> skillIds;

        /**
         * 节点模型编码。
         */
        private String modelCode;

        /** 自循环节点的执行上限；防止无限迭代。 */
        private Integer maxIterations;

        /** 当前节点允许运行时采用的路由策略集合。 */
        private List<String> enabledStrategies;

        /** 当前节点允许直接到达的目标节点，防止运行时选择图外目标。 */
        private List<String> allowedTargetNodeIds;

        /** 没有业务路由命中时使用的默认目标节点。 */
        private String defaultTargetNodeId;

        /** 提供给智能路由模型的业务选择说明。 */
        private String routeInstruction;

        /** 冻结后的节点 RAG 工具开关；null 表示继承运行级设置。 */
        private Boolean ragToolEnabled;

        /** 整个运行中该节点允许被调度的次数。 */
        private Integer maxVisits;

        /** 当前节点是否没有任何后续出边。 */
        private Boolean terminal;

        /** 当前节点可供智能路由工具选择的业务路由。 */
        private List<RouteDescriptor> routeDescriptors;
    }

    /** 冻结的节点业务路由描述符。 */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RouteDescriptor {

        /** 模型可见并提交的主路由键。 */
        private String routeKey;

        /** 只用于服务端精确兼容匹配的受控别名。 */
        private List<String> routeAliases;

        /** 该路由对应的工作流边。 */
        private String edgeId;

        /** 该路由命中的目标节点。 */
        private String targetNodeId;

        /** 目标节点展示名，用于生成模型可理解的工具说明。 */
        private String targetNodeName;
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

        /** 边的路由类型，决定运行时在哪一种策略中评估它。 */
        private String routeType;

        /** 节点建议或模型路由使用的主路由键。 */
        private String routeKey;

        /** 只参与显式路由键精确匹配的兼容别名。 */
        private List<String> routeAliases;

        /** 已通过编译器白名单校验的条件表达式。 */
        private String conditionExpression;

        /** 同类路由中的评估顺序，数值越小越优先。 */
        private Integer priority;
    }
}
