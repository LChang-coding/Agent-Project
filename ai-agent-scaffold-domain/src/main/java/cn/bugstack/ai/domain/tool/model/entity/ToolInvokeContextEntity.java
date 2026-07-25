package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用上下文实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeContextEntity {

    /** 可信租户 ID。 */
    private String tenantId;
    /** 可信用户 ID。 */
    private String userId;
    /** 业务会话 ID。 */
    private String sessionId;
    /** 工作流目标 ID；普通 Agent 时为 Agent ID 兼容值。 */
    private String workflowId;
    /** ADK 推理调用 ID。 */
    private String invocationId;
    /** 聊天运行 ID。 */
    private String runId;
    /** 模型作出工具决策时看到的上下文版本。 */
    private Long contextRevision;
    /** 模型函数调用 ID。 */
    private String functionCallId;
    /** 入口全链路 ID。 */
    private String traceId;
}
