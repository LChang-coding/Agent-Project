package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具调用日志持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ToolCallLogPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 调用用户ID
     */
    private String userId;

    /**
     * 会话业务ID
     */
    private String sessionId;

    /**
     * 所属运行ID
     */
    private String runId;

    /**
     * 工作流业务ID
     */
    private String workflowId;

    /**
     * 工具类型：skill/mcp
     */
    private String toolType;

    /**
     * 工具业务ID
     */
    private String toolId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 调用时工具版本号
     */
    private String version;

    /**
     * ADK 调用ID
     */
    private String invocationId;

    /**
     * ADK 工具调用ID
     */
    private String functionCallId;

    /**
     * 外部调用幂等键
     */
    private String idempotencyKey;

    /**
     * 链路ID
     */
    private String traceId;

    /**
     * 工具入参
     */
    private String inputJson;

    /**
     * 工具出参
     */
    private String outputJson;

    /**
     * 调用状态：started/success/failed/timeout
     */
    private String status;

    /**
     * 外部调用开始时间
     */
    private java.time.LocalDateTime startedAt;

    /**
     * 错误类型
     */
    private String errorType;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 调用耗时毫秒
     */
    private Long costMs;

    /**
     * 扩展信息
     */
    private String metadata;
}
