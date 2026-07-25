package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 单次模型调用的幂等 Token 用量账本。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ModelUsagePO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 用量归属用户ID
     */
    private String userId;

    /**
     * 会话业务ID
     */
    private String sessionId;

    /**
     * 业务运行ID。
     */
    private String runId;

    /**
     * 单次模型调用幂等ID。
     */
    private String callId;

    /**
     * 消息业务ID
     */
    private String messageId;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent 名称
     */
    private String agentName;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 模型调用ID
     */
    private String invocationId;

    /**
     * 模型供应商
     */
    private String provider;

    /**
     * 模型版本
     */
    private String modelVersion;

    /** 用量来源类型，如 chat/workflow/tool。 */
    private String usageType;

    /** running/success/failed/cancelled 调用状态。 */
    private String callStatus;

    /** 模型返回的停止原因。 */
    private String finishReason;

    /**
     * 输入 token 数
     */
    private Integer promptTokens;

    /**
     * 输出 token 数
     */
    private Integer candidateTokens;

    /**
     * 总 token 数
     */
    private Integer totalTokens;

    /**
     * 思考 token 数
     */
    private Integer thoughtsTokens;

    /**
     * 工具调用提示 token 数
     */
    private Integer toolUsePromptTokens;

    /**
     * 链路ID
     */
    private String traceId;

    /**
     * 扩展信息
     */
    private String metadata;
}
