package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.WorkflowRouteIntentPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 工作流路由意图 DAO。 */
public interface IWorkflowRouteIntentDao {
    int insertIgnore(WorkflowRouteIntentPO intent);
    WorkflowRouteIntentPO queryByNode(@Param("tenantId") String tenantId, @Param("runId") String runId,
                                      @Param("nodeExecutionId") String nodeExecutionId);
    WorkflowRouteIntentPO queryByFunctionCall(@Param("tenantId") String tenantId,
                                              @Param("functionCallId") String functionCallId);
    int consume(@Param("tenantId") String tenantId, @Param("runId") String runId,
                @Param("nodeExecutionId") String nodeExecutionId, @Param("expectedStatus") String expectedStatus,
                @Param("consumedAt") LocalDateTime consumedAt);
}
