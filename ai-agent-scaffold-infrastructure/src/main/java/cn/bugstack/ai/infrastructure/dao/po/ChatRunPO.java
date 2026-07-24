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

    private String runId;
    private String turnId;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String sourceType;
    private String sourceId;
    private Boolean ragEnabled;
    private String traceId;
    private String status;
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
