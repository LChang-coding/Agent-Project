package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次工具调用使用的可信身份、运行坐标和能力范围。
 *
 * <p>{@code GatewayToolset} 从服务端运行状态构造初始上下文，{@code GatewayAdkTool} 再使用 ADK
 * 运行时提供的可信值补全。模型函数参数不能覆盖这里的租户、用户、运行、节点或知识库范围。</p>
 *
 * <p>{@code ToolGateway} 使用该对象校验身份和分发范围，授权服务使用运行标识、上下文版本和函数调用标识
 * 完成取消检查、过期检查和幂等登记。工具地址、参数 Schema 和模型提交的业务参数不属于该对象。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeContextEntity {

    /** 可信租户编号；为空时无法保证数据隔离和审计归属，工具网关必须拒绝执行。 */
    private String tenantId;
    /** 可信用户编号；用于工具授权和调用审计，为空时拒绝执行。 */
    private String userId;
    /** 业务会话编号；用于按会话查询工具调用日志，缺失时不影响工具授权。 */
    private String sessionId;
    /** 本轮执行目标编号；工作流运行保存工作流编号，普通 Agent 运行兼容保存 Agent 编号。 */
    private String workflowId;
    /** ADK 单次推理调用编号；同一轮推理的工具调用使用该值关联。 */
    private String invocationId;
    /** 权威运行编号；有值时工具执行前锁定运行并检查取消状态。 */
    private String runId;
    /** 模型决定调用工具时读取的上下文版本；版本过期时拒绝执行。 */
    private Long contextRevision;
    /** 模型函数调用编号；与运行编号共同生成工具调用幂等键。 */
    private String functionCallId;
    /** 入口请求的跟踪编号，用于关联编排、工具审计和外部请求日志。 */
    private String traceId;
    /** 本轮冻结的 RAG 调用方式；AGENT_TOOL 才允许模型主动调用检索工具。 */
    private String ragInvocationMode;

    /** 节点级 RAG 工具开关；null 表示继承运行级设置。 */
    private Boolean ragToolEnabled;

    /** 当前工作流节点允许使用的 MCP 工具 ID；null 表示非工作流兼容上下文。 */
    private List<String> workflowMcpIds;
    /** 当前工作流节点允许使用的 Skill ID；null 表示非工作流兼容上下文。 */
    private List<String> workflowSkillIds;
    /** 本轮冻结的 RAG 选择模式，例如 OFF、AUTO 或 MANUAL。 */
    private String ragMode;

    /** 保存本次检索证据时使用的模型调用标识。 */
    private String ragEvidenceInvocationId;

    /** 知识库绑定目标类型，例如 AGENT 或 WORKFLOW。 */
    private String ragTargetType;

    /** 知识库绑定目标标识，与目标类型共同限定检索范围。 */
    private String ragTargetId;

    /** 本轮运行允许检索的绑定标识列表。 */
    private List<String> ragBindingIds;

    /** 当前运行的工作流类型；INTELLIGENT 才可能使用路由工具。 */
    private String workflowKind;

    /** 当前运行冻结的路由协议版本。 */
    private String routingProtocolVersion;

    /** 当前节点是否没有后续出边；终点节点不允许选择路由。 */
    private Boolean terminalNode;

    /** 当前节点允许模型选择的业务路由，由服务端工作流定义生成。 */
    private List<cn.bugstack.ai.domain.tool.service.PlatformToolResolver.RouteDescriptor> routeDescriptors;

    /** 本次逻辑节点执行标识，用于限定路由意图的幂等范围。 */
    private String nodeExecutionId;

    /** 产生当前工具调用的工作流节点标识。 */
    private String sourceNodeId;

    /** 冻结工作流定义的摘要，用于拒绝跨定义版本消费路由意图。 */
    private String definitionHash;

    /** 本次运行冻结的工作流版本，平台路由工具会校验为正整数。 */
    private String workflowVersion;

    /** 是否处于仅允许修复路由选择的模型调用阶段。 */
    private Boolean routeRepairOnly;

    /** 当前执行 Agent 的服务端可信编号。 */
    private String agentId;

    /** 当前 Agent 的冻结编排角色；只有 SUPERVISOR 可见子 Agent 工具。 */
    private String orchestrationRole;

    /** 当前主 Agent 可委派的子 Agent 白名单，模型调用参数不能覆盖。 */
    private List<String> allowedSubAgentIds;

    /** 服务端跨回调延续的原始主运行编号。 */
    private String orchestrationRootRunId;
}
