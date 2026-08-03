package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.IntelligentWorkflowRunPO;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 智能工作流事件与运行序号 DAO。 */
public interface IWorkflowRunEventDao {

    int insertRun(IntelligentWorkflowRunPO run);

    IntelligentWorkflowRunPO queryRun(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("runId") String runId);

    int updateRunState(@Param("run") IntelligentWorkflowRunPO run,
                       @Param("expectedRevision") long expectedRevision);

    int cancelActiveRun(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("runId") String runId,
                        @Param("finishedAt") java.time.LocalDateTime finishedAt);

    IntelligentWorkflowRunPO lockRun(@Param("tenantId") String tenantId,
                                      @Param("userId") String userId,
                                      @Param("runId") String runId);

    int advanceSequence(@Param("tenantId") String tenantId,
                        @Param("runId") String runId,
                        @Param("expectedRevision") long expectedRevision);

    int insert(WorkflowRunEventPO event);

    List<WorkflowRunEventPO> queryAfter(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("runId") String runId,
                                        @Param("afterSequence") long afterSequence,
                                        @Param("limit") int limit);

    Long queryOldestSequence(@Param("tenantId") String tenantId,
                             @Param("userId") String userId,
                             @Param("runId") String runId);
}
