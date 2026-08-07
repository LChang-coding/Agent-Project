package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次具体工具调用的完整身份与运行坐标，是「谁、在哪个会话、哪次运行、哪次模型函数调用」的答案。
 *
 * <p>所属层次：工具领域的实体（调用上下文对象），本身不落库，但里面每个字段最后都会被抄进工具调用日志。</p>
 *
 * <p>谁构造它：{@code GatewayToolset} 先按当前 state 建一个「回退版」，{@code GatewayAdkTool} 在模型真正发起调用时，
 * 优先取 ADK 运行时给的值、取不到才用回退版，逐字段拼出最终对象。</p>
 *
 * <p>谁消费它：{@code ToolGateway} 用它做身份完整性校验；{@code ToolDispatchAuthorizationService} 用它给运行加锁、
 * 生成幂等键并写审计。</p>
 *
 * <p>安全铁律：这里的每个字段都只能来自服务端可信来源（登录态、编排层写入的 state、ADK 运行时），
 * 绝不能采用大模型给出的函数参数。模型是可以被提示词注入操纵的，它完全可能把 tenantId 填成别人的租户，
 * 一旦采信就等于把跨租户越权的钥匙交给了模型。</p>
 *
 * <p>它不负责什么：不含工具本身的配置（地址、命令、schema 在 {@code ToolCatalogEntity} 里），
 * 也不含模型给的业务入参。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeContextEntity {

    /** 可信租户编号；工具网关在领执行权之前就要检查它，为空直接拒绝，因为一旦缺失后续的数据隔离和审计归属全都失效。 */
    private String tenantId;
    /** 可信用户编号；决定这次调用算在谁头上，也是判断能否使用私有工具的依据，为空同样直接拒绝执行。 */
    private String userId;
    /** 业务会话编号；调用日志按它归档，前端才能在这段对话里列出用过哪些工具，缺失只影响可观测性，不阻断执行。 */
    private String sessionId;
    /** 本轮执行目标编号；跑工作流时是工作流编号，跑普通 Agent 时兼容存 Agent 编号，写进审计用于区分调用来源。 */
    private String workflowId;
    /** ADK 单次推理的调用编号；同一轮推理里的多个工具调用共享它，排查「模型这一轮到底做了什么」时靠它聚拢。 */
    private String invocationId;
    /** 权威运行编号；有值时工具产生副作用前会先给运行加行锁并检查是否已取消，为空则退化成不可取消、不可幂等的裸调用。 */
    private String runId;
    /** 模型作出「要调这个工具」决定时看到的上下文版本；用它挡住基于过期上下文的迟到调用，避免上下文已被改写还照旧执行。 */
    private Long contextRevision;
    /** 大模型这次函数调用的编号；它和运行编号一起参与幂等键计算，是「同一个函数调用只执行一次」的关键输入。 */
    private String functionCallId;
    /** 入口全链路追踪编号；从 HTTP 入口一路带到工具审计日志，用户报障时凭它把前端、编排、工具三段日志串起来。 */
    private String traceId;
    private String ragInvocationMode;

    /** 节点级 RAG 工具开关；null 表示继承运行级设置。 */
    private Boolean ragToolEnabled;

    /** 当前工作流节点允许使用的 MCP 工具 ID；null 表示非工作流兼容上下文。 */
    private List<String> workflowMcpIds;
    private String ragMode;
    private String ragEvidenceInvocationId;
    private String ragTargetType;
    private String ragTargetId;
    private List<String> ragBindingIds;
    private String workflowKind;
    private String routingProtocolVersion;
    private Boolean terminalNode;
    private List<cn.bugstack.ai.domain.tool.service.PlatformToolResolver.RouteDescriptor> routeDescriptors;
    private String nodeExecutionId;
    private String sourceNodeId;
    private String definitionHash;
    private String workflowVersion;
    private Boolean routeRepairOnly;
}
