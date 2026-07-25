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
    /** 调用所属租户。 */
    private String tenantId;
    /** 调用所属用户。 */
    private String userId;
    /** 调用所属会话。 */
    private String sessionId;
    /** 调用所属运行。 */
    private String runId;
    /** 供应商调用或平台调用唯一键。 */
    private String callId;
    /** 单次模型推理轮次标识。 */
    private String invocationId;
    /** 发起调用的 Agent。 */
    private String agentId;
    /** Agent 展示名快照。 */
    private String agentName;
    /** 模型应用名称。 */
    private String appName;
    /** 模型供应商。 */
    private String provider;
    /** 实际模型版本。 */
    private String modelVersion;
    /** 聊天、摘要或其他用量分类。 */
    private String usageType;
    /** running、success、failed 或 cancelled。 */
    private String callStatus;
    /** 模型完成原因。 */
    private String finishReason;
    /** 输入 Token。 */
    private Integer promptTokens;
    /** 候选输出 Token。 */
    private Integer candidateTokens;
    /** 总 Token；缺失时由输入和输出相加。 */
    private Integer totalTokens;
    /** 推理思考 Token。 */
    private Integer thoughtsTokens;
    /** 工具调用提示 Token。 */
    private Integer toolUsePromptTokens;
    /** 全链路追踪标识。 */
    private String traceId;
    /** 首次记录时间。 */
    private LocalDateTime createTime;
}
