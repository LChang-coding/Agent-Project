package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.WorkflowEventCursorPO;
import org.apache.ibatis.annotations.Param;

/** 通用工作流事件序号游标 DAO。 */
public interface IWorkflowEventCursorDao {

    /** 从已存在的工作流聊天运行补建事件游标，重复插入由唯一键忽略。 */
    int insertFromWorkflowChatRun(@Param("tenantId") String tenantId,
                                  @Param("userId") String userId,
                                  @Param("runId") String runId);

    /** 按可信运行范围加行锁读取游标，使同一运行的序号分配串行执行。 */
    WorkflowEventCursorPO lockCursor(@Param("tenantId") String tenantId,
                                     @Param("userId") String userId,
                                     @Param("runId") String runId);

    /** 在 revision 匹配时推进普通事件序号。 */
    int advanceSequence(@Param("tenantId") String tenantId,
                        @Param("userId") String userId,
                        @Param("runId") String runId,
                        @Param("traceId") String traceId,
                        @Param("expectedRevision") long expectedRevision);

    /** 推进序号并记录终态事件类型，终态写入后不再允许继续分配。 */
    int advanceTerminalSequence(@Param("tenantId") String tenantId,
                                @Param("userId") String userId,
                                @Param("runId") String runId,
                                @Param("traceId") String traceId,
                                @Param("terminalEventType") String terminalEventType,
                                @Param("expectedRevision") long expectedRevision);

    /** 将扩展运行快照的下一个序号同步到已成功分配的事件序号之后。 */
    int syncIntelligentRunSequence(@Param("tenantId") String tenantId,
                                   @Param("userId") String userId,
                                   @Param("runId") String runId,
                                   @Param("traceId") String traceId,
                                   @Param("nextSequence") long nextSequence);
}
