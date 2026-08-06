package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话运行持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatRunPO extends BasePO {

    /** 一次可取消、可续接的运行 ID。 */
    private String runId;
    /** 同一用户轮次跨续接运行共享的 ID。 */
    private String turnId;
    /** 运行所属租户。 */
    private String tenantId;
    /** 发起运行的可信用户。 */
    private String userId;
    /** 运行所属会话。 */
    private String sessionId;
    /** 入口类型：agent 或 workflow。 */
    private String sourceType;
    /** Agent 或 Workflow 的业务 ID。 */
    private String sourceId;
    /** 运行开始时冻结的 RAG 开关。 */
    private Boolean ragEnabled;
    /** 运行开始时冻结的 RAG 绑定选择模式。 */
    private String ragMode;
    /** 运行冻结的 RAG 调用方式。 */
    private String ragInvocationMode;
    /** 运行开始时冻结的会话 RAG 策略修订号。 */
    private Long ragPolicyRevision;
    /** 运行实际使用的绑定 ID 快照。 */
    private String ragBindingIdsJson;
    /** 贯穿 HTTP、RAG、模型和工具的链路 ID。 */
    private String traceId;
    /** 运行状态机当前状态。 */
    private String status;
    /** 运行行乐观锁版本。 */
    private Integer version;
    /** 创建运行时读取的上下文修订号。 */
    private Long baseContextRevision;
    /** 当前运行已推进到的上下文修订号。 */
    private Long currentContextRevision;
    /** steer 前被接替的运行 ID。 */
    private String predecessorRunId;
    /** steer 后接替当前运行的 ID。 */
    private String successorRunId;
    /** 本轮用户消息 ID。 */
    private String userMessageId;
    /** 续接运行附加的引导指令。 */
    private String steerInstruction;
    /** 完成、失败或取消原因。 */
    private String terminalReason;
    /** 用户首次请求取消的时间。 */
    private LocalDateTime cancelRequestedAt;
    /** 运行实际开始执行时间。 */
    private LocalDateTime startedAt;
    /** 运行进入终态时间。 */
    private LocalDateTime finishedAt;
}
