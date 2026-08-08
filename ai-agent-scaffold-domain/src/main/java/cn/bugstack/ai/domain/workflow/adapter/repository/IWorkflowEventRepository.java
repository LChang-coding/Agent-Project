package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;

import java.util.List;

/** 持久化并回放 workflow 类 chat_run 的通用业务事件。 */
public interface IWorkflowEventRepository {

    /**
     * 为事件分配运行内递增序号并持久化。
     *
     * @param event 尚未分配序号的工作流事件
     * @return 包含持久化序号的事件
     */
    WorkflowRunEventEntity append(WorkflowRunEventEntity event);

    /**
     * 按运行内序号续读事件，供 SSE 重连和跨实例追赶使用。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待续读的工作流运行
     * @param afterSequence 只返回大于该值的事件；零表示从最早可用事件开始
     * @param limit 单次最多返回的事件数，防止慢订阅者一次读取过多记录
     * @return 按序号升序排列的事件
     */
    List<WorkflowRunEventEntity> queryAfter(String tenantId, String userId, String runId,
                                             long afterSequence, int limit);

    /**
     * 查询仍在保留期内的最早事件序号。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待查询的工作流运行
     * @return 最早可续读序号；运行尚无事件时返回空
     */
    Long queryOldestSequence(String tenantId, String userId, String runId);

    /**
     * 查询运行唯一的完成、失败或取消事件。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待查询的工作流运行
     * @return 已持久化的终态事件；运行尚未结束时返回空
     */
    WorkflowRunEventEntity queryTerminal(String tenantId, String userId, String runId);
}
