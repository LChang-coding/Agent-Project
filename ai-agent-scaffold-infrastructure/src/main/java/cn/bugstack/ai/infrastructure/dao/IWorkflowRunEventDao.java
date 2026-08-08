package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.IntelligentWorkflowRunPO;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 智能运行扩展状态与通用工作流事件 DAO。 */
public interface IWorkflowRunEventDao {

    /** 创建智能工作流运行扩展快照。 */
    int insertRun(IntelligentWorkflowRunPO run);

    /** 在可信租户和用户范围内查询运行快照。 */
    IntelligentWorkflowRunPO queryRun(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("runId") String runId);

    /** 按 expectedRevision 更新运行状态，返回值用于识别并发冲突。 */
    int updateRunState(@Param("run") IntelligentWorkflowRunPO run,
                       @Param("expectedRevision") long expectedRevision);

    /** 仅取消尚未进入终态的运行。 */
    int cancelActiveRun(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("runId") String runId,
                        @Param("finishedAt") java.time.LocalDateTime finishedAt);

    /** 写入已经分配唯一序号的工作流事件。 */
    int insert(WorkflowRunEventPO event);

    /** 按序号升序查询客户端游标之后的事件。 */
    List<WorkflowRunEventPO> queryAfter(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("runId") String runId,
                                        @Param("afterSequence") long afterSequence,
                                        @Param("limit") int limit);

    /** 查询仍保留在数据库中的最早事件序号。 */
    Long queryOldestSequence(@Param("tenantId") String tenantId,
                             @Param("userId") String userId,
                             @Param("runId") String runId);

    /** 查询运行已经持久化的终态事件。 */
    WorkflowRunEventPO queryTerminal(@Param("tenantId") String tenantId,
                                     @Param("userId") String userId,
                                     @Param("runId") String runId);
}
