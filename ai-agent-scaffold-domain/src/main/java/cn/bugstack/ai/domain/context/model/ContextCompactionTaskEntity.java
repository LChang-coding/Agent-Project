package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文压缩执行账本。
 */
@Data
@Builder
public class ContextCompactionTaskEntity {

    private String taskId;
    private String taskKey;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String runId;
    private Integer fromSequence;
    private Integer toSequence;
    private Integer expectedMemoryVersion;
    private Long baseContextRevision;
    private String coverageHash;
    private String policyVersion;
    private ContextCompactionTaskStatus status;
    private Integer attemptCount;
    private String errorMessage;
    private String traceId;

    /**
     * 转为 Kafka 命令；无参数；返回异步压缩通知。
     */
    public ContextCompactionCommand toCommand() {
        return new ContextCompactionCommand(taskId, tenantId, userId, sessionId, fromSequence, toSequence, expectedMemoryVersion, policyVersion);
    }
}
