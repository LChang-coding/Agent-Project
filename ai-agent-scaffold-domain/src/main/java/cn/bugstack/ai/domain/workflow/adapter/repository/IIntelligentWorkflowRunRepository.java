package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;

/** 智能工作流运行扩展仓储。 */
public interface IIntelligentWorkflowRunRepository {

    /**
     * 新建智能工作流扩展运行记录。
     *
     * @param run 已固定定义版本、预算和根节点的运行
     * @return 插入的记录数
     */
    int insert(IntelligentWorkflowRunEntity run);

    /**
     * 按可信身份查询智能工作流运行。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待查询的运行标识
     * @return 匹配的运行；不存在或无权访问时返回空
     */
    IntelligentWorkflowRunEntity query(String tenantId, String userId, String runId);

    /**
     * 使用预期修订号更新运行节点、预算和状态。
     *
     * @param run 包含下一状态和新修订号的运行实体
     * @param expectedRevision 更新前读取的修订号
     * @return 1 表示更新成功，0 表示状态已被并发修改
     */
    int updateState(IntelligentWorkflowRunEntity run, long expectedRevision);

    /**
     * 将任意非终态运行原子更新为取消，不依赖调用方可能过期的修订号。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 待取消的运行标识
     * @param finishedAt 取消实际生效的时间
     * @return 1 表示本次完成取消，0 表示运行不存在或已经进入终态
     */
    int cancelActive(String tenantId, String userId, String runId, java.time.LocalDateTime finishedAt);
}
