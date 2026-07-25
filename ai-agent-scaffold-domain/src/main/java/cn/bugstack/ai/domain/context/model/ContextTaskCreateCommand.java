package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文压缩任务创建命令。
 */
@Data
@Builder
public class ContextTaskCreateCommand {

    /** 任务所属租户。 */
    private String tenantId;
    /** 任务所属用户。 */
    private String userId;
    /** 任务所属会话。 */
    private String sessionId;
    /** 触发任务的运行。 */
    private String runId;
    /** 压缩范围起始序号。 */
    private Integer fromSequence;
    /** 压缩范围结束序号。 */
    private Integer toSequence;
    /** 创建时预期的长期摘要版本。 */
    private Integer expectedMemoryVersion;
    /** 创建时会话上下文版本。 */
    private Long baseContextRevision;
    /** 范围内有效消息摘要。 */
    private String coverageHash;
    /** 压缩策略版本。 */
    private String policyVersion;
    /** 原始触发链路标识。 */
    private String traceId;
}
