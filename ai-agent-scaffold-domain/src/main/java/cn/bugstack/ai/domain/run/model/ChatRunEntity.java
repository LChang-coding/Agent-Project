package cn.bugstack.ai.domain.run.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话运行实体。
 * <p>保存一次可取消、可引导执行的持久化状态。</p>
 */
@Data
@Builder
public class ChatRunEntity {

    /** 一次可取消执行的业务标识。 */
    private String runId;
    /** 一轮用户意图及其后继引导链的标识。 */
    private String turnId;
    /** 运行所属租户。 */
    private String tenantId;
    /** 运行所属用户。 */
    private String userId;
    /** 运行绑定的会话。 */
    private String sessionId;
    /** agent 或 workflow。 */
    private String sourceType;
    /** 实际执行的 Agent 或工作流标识。 */
    private String sourceId;
    /** 本轮创建时固化的会话RAG设置，运行中不随会话开关变化。 */
    private Boolean ragEnabled;
    /** 本轮固化的RAG策略模式：OFF/AUTO/MANUAL。 */
    private String ragMode;
    /** 本轮固化的会话RAG策略版本。 */
    private Long ragPolicyRevision;
    /** 本轮固化的有效绑定；AUTO也显式展开，避免运行中绑定变化造成漂移。 */
    private List<String> ragBindingIds;
    /** 本轮根链路ID，用于跨线程、模型、RAG和工具检索整条链路。 */
    private String traceId;
    /** 持久化运行状态机当前状态。 */
    private RunStatus status;
    /** 每次条件更新递增的乐观锁版本。 */
    private Integer version;
    /** 创建本轮时观察到的会话上下文版本。 */
    private Long baseContextRevision;
    /** 运行继续推理所要求的当前上下文版本。 */
    private Long currentContextRevision;
    /** 引导链中的前一运行。 */
    private String predecessorRunId;
    /** 当前运行被引导替代后创建的后继运行。 */
    private String successorRunId;
    /** 与运行原子绑定的用户消息。 */
    private String userMessageId;
    /** 后继运行需要合入提示词的引导指令。 */
    private String steerInstruction;
    /** 完成、失败、取消或替代原因。 */
    private String terminalReason;
    /** 用户首次请求取消或引导的时间。 */
    private LocalDateTime cancelRequestedAt;
    /** 真正开始执行模型链路的时间。 */
    private LocalDateTime startedAt;
    /** 进入终态的时间。 */
    private LocalDateTime finishedAt;
}
