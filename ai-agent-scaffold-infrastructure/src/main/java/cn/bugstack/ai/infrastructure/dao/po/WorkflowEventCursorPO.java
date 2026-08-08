package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/** workflow_run_event_cursor 持久化对象。 */
@Data
public class WorkflowEventCursorPO {
    /** 数据库自增主键。 */
    private Long id;
    /** 游标所属租户。 */
    private String tenantId;
    /** 游标所属用户。 */
    private String userId;
    /** 游标对应的工作流运行。 */
    private String runId;
    /** 运行根链路标识。 */
    private String traceId;
    /** 下一条事件应分配的序号。 */
    private Long nextSequence;
    /** 已写入的终态事件类型，空值表示事件流仍开放。 */
    private String terminalEventType;
    /** 终态事件占用的序号。 */
    private Long terminalSequence;
    /** 并发推进游标使用的乐观锁版本。 */
    private Long revision;
}
