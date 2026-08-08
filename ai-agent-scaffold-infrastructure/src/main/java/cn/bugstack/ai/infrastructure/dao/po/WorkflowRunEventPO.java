package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** workflow_run_event 表持久化对象。 */
@Data
public class WorkflowRunEventPO {
    /** 数据库自增主键。 */
    private Long id;
    /** 事件所属租户。 */
    private String tenantId;
    /** 事件所属用户。 */
    private String userId;
    /** 事件所属运行。 */
    private String runId;
    /** 单个事件的唯一标识。 */
    private String eventId;
    /** 运行内严格递增的事件序号。 */
    private Long sequence;
    /** 事件载荷采用的协议版本。 */
    private String schemaVersion;
    /** 决定客户端解释方式的事件类型。 */
    private String eventType;
    /** 产生事件的节点执行标识。 */
    private String nodeExecutionId;
    /** 产生事件的工作流节点。 */
    private String nodeId;
    /** 按协议序列化的事件业务数据。 */
    private String payloadJson;
    /** 事件所在运行根链路的标识。 */
    private String traceId;
    /** 业务事件发生时间。 */
    private LocalDateTime occurredAt;
    /** 事件停止提供历史重放的时间。 */
    private LocalDateTime expiresAt;
}
