package cn.bugstack.ai.domain.usage.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型调用用量实体。
 */
@Data
@Builder
public class ModelUsageEntity {
    private String tenantId;
    private String userId;
    private String sessionId;
    private String runId;
    private String callId;
    private String invocationId;
    private String agentId;
    private String agentName;
    private String appName;
    private String provider;
    private String modelVersion;
    private String usageType;
    private String callStatus;
    private String finishReason;
    private Integer promptTokens;
    private Integer candidateTokens;
    private Integer totalTokens;
    private Integer thoughtsTokens;
    private Integer toolUsePromptTokens;
    private String traceId;
    private LocalDateTime createTime;
}
