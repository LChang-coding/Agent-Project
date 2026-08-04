package cn.bugstack.ai.domain.workflow.adapter.repository;

/** 从 workflow 类 chat_run 校验运行归属并分配严格递增的事件序号。 */
public interface IWorkflowEventCursorRepository {

    /**
     * 分配事件序号。
     *
     * @param tenantId 可信租户ID
     * @param userId 可信用户ID
     * @param runId 工作流运行ID
     * @param traceId 运行根链路ID
     * @return 本次分配的序号
     */
    long allocate(String tenantId, String userId, String runId, String traceId, String eventType);
}
