package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 可持久化、可重放的通用工作流业务事件。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunEventEntity {

    /** 事件所属租户。 */
    private String tenantId;

    /** 事件所属运行的用户。 */
    private String userId;

    /** 事件所属工作流运行。 */
    private String runId;

    /** 事件全局标识，用于重放去重。 */
    private String eventId;

    /** 运行内严格递增序号，用于 SSE 断线续读。 */
    private Long sequence;

    /** 事件载荷协议版本。 */
    private String schemaVersion;

    /** 事件业务类型，决定前端和恢复流程如何解释载荷。 */
    private String eventType;

    /** 事件关联的逻辑节点执行；运行级事件可以为空。 */
    private String nodeExecutionId;

    /** 事件关联的工作流节点；运行级事件可以为空。 */
    private String nodeId;

    /** 按事件类型定义的 JSON 载荷。 */
    private String payloadJson;

    /** 与根工作流运行一致的跟踪标识。 */
    private String traceId;

    /** 业务事件实际发生时间。 */
    private LocalDateTime occurredAt;

    /** 事件保留截止时间，续读超过该时间后需查询最终状态。 */
    private LocalDateTime expiresAt;
}
