package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具调用日志实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLogEntity {

    /** 数据库主键。 */
    private Long id;
    /** 调用所属租户。 */
    private String tenantId;
    /** 发起调用的可信用户。 */
    private String userId;
    /** 关联业务会话。 */
    private String sessionId;
    /** 关联聊天运行。 */
    private String runId;
    /** Agent 调用时可为空，工作流调用时为目标 ID。 */
    private String workflowId;
    /** skill 或 mcp。 */
    private String toolType;
    /** 工具稳定业务 ID。 */
    private String toolId;
    /** 调用时展示名称快照。 */
    private String toolName;
    /** 调用的冻结版本号。 */
    private String version;
    /** ADK 本次推理调用 ID。 */
    private String invocationId;
    /** 模型函数调用 ID。 */
    private String functionCallId;
    /** 阻止同一函数调用重复产生副作用的唯一键。 */
    private String idempotencyKey;
    /** 入口全链路 ID。 */
    private String traceId;
    /** 审计后的输入 JSON。 */
    private String inputJson;
    /** 审计后的输出 JSON。 */
    private String outputJson;
    /** started/success/failed。 */
    private String status;
    /** 首次取得执行权的时间。 */
    private LocalDateTime startedAt;
    /** 异常类型短码。 */
    private String errorType;
    /** 截断后的异常摘要。 */
    private String errorMessage;
    /** 工具执行耗时毫秒。 */
    private Long costMs;
    /** 扩展元数据 JSON。 */
    private String metadata;
    /** 日志创建时间。 */
    private LocalDateTime createTime;
}
