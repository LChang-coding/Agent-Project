package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.WorkflowEventCursorPO;
import org.apache.ibatis.annotations.Param;

/** 通用工作流事件序号游标 DAO。 */
public interface IWorkflowEventCursorDao {

    int insertFromWorkflowChatRun(@Param("tenantId") String tenantId,
                                  @Param("userId") String userId,
                                  @Param("runId") String runId);

    WorkflowEventCursorPO lockCursor(@Param("tenantId") String tenantId,
                                     @Param("userId") String userId,
                                     @Param("runId") String runId);

    int advanceSequence(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("runId") String runId,
                        @Param("traceId") String traceId,
                        @Param("expectedRevision") long expectedRevision);

    int advanceTerminalSequence(@Param("tenantId") String tenantId,
                                @Param("userId") String userId,
                                @Param("runId") String runId,
                                @Param("traceId") String traceId,
                                @Param("terminalEventType") String terminalEventType,
                                @Param("expectedRevision") long expectedRevision);

    int syncIntelligentRunSequence(@Param("tenantId") String tenantId,
                                   @Param("userId") String userId,
                                   @Param("runId") String runId,
                                   @Param("traceId") String traceId,
                                   @Param("nextSequence") long nextSequence);
}
