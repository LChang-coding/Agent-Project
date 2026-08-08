package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.WorkflowRouteIntentPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 工作流路由意图 DAO。 */
public interface IWorkflowRouteIntentDao {
    /** 按节点执行和函数调用唯一键登记意图，重复调用不会覆盖已有选择。 */
    int insertIgnore(WorkflowRouteIntentPO intent);

    /** 查询指定运行节点已经登记的路由意图。 */
    WorkflowRouteIntentPO queryByNode(@Param("tenantId") String tenantId, @Param("runId") String runId,
                                      @Param("nodeExecutionId") String nodeExecutionId);

    /** 按模型函数调用标识查询意图，用于重放和幂等判断。 */
    WorkflowRouteIntentPO queryByFunctionCall(@Param("tenantId") String tenantId,
                                              @Param("functionCallId") String functionCallId);

    /** 仅在状态仍等于 expectedStatus 时原子消费意图。 */
    int consume(@Param("tenantId") String tenantId, @Param("runId") String runId,
                @Param("nodeExecutionId") String nodeExecutionId, @Param("expectedStatus") String expectedStatus,
                @Param("consumedAt") LocalDateTime consumedAt);
}
