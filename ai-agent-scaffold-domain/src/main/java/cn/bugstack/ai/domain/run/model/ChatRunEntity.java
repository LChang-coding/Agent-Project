package cn.bugstack.ai.domain.run.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话运行实体。
 * <p>保存一次可取消、可引导执行的持久化状态。</p>
 */
@Data
@Builder
public class ChatRunEntity {

    private String runId;
    private String turnId;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String sourceType;
    private String sourceId;
    /** 本轮创建时固化的会话RAG设置，运行中不随会话开关变化。 */
    private Boolean ragEnabled;
    /** 本轮根链路ID，用于跨线程、模型、RAG和工具检索整条链路。 */
    private String traceId;
    private RunStatus status;
    private Integer version;
    private Long baseContextRevision;
    private Long currentContextRevision;
    private String predecessorRunId;
    private String successorRunId;
    private String userMessageId;
    private String steerInstruction;
    private String terminalReason;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
