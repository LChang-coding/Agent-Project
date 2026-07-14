package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文压缩任务创建命令。
 */
@Data
@Builder
public class ContextTaskCreateCommand {

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
    private String traceId;
}
