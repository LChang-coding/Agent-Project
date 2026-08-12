package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTaskPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 临时子 Agent 任务和编排 Outbox DAO。 */
public interface ISubagentTaskDao {
    int insertTask(SubagentTaskPO task);
    int insertOutbox(AgentOrchestrationOutboxPO event);
    List<SubagentTaskPO> queryByFunctionCall(@Param("tenantId") String tenantId,
                                             @Param("parentRunId") String parentRunId,
                                             @Param("functionCallId") String functionCallId);
    List<SubagentTaskPO> queryByIds(@Param("tenantId") String tenantId,
                                    @Param("parentRunId") String parentRunId,
                                    @Param("taskIds") List<String> taskIds);
    List<SubagentTaskPO> queryBySession(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("parentSessionId") String parentSessionId,
                                        @Param("limit") int limit);
    int claim(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
              @Param("workerId") String workerId, @Param("now") LocalDateTime now,
              @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    SubagentTaskPO queryOwned(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                              @Param("workerId") String workerId);
    int bindChildSession(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                         @Param("workerId") String workerId, @Param("fencingToken") long fencingToken,
                         @Param("childSessionId") String childSessionId);
    int renewLease(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                   @Param("workerId") String workerId, @Param("fencingToken") long fencingToken,
                   @Param("now") LocalDateTime now, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    int complete(@Param("task") SubagentTaskPO task, @Param("workerId") String workerId,
                 @Param("fencingToken") long fencingToken);
    int cancel(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
               @Param("taskIds") List<String> taskIds, @Param("cancelledAt") LocalDateTime cancelledAt);
    List<AgentOrchestrationOutboxPO> queryDueOutbox(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claimOutbox(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                    @Param("owner") String owner, @Param("now") LocalDateTime now,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    AgentOrchestrationOutboxPO queryOwnedOutbox(@Param("tenantId") String tenantId,
                                                @Param("eventId") String eventId,
                                                @Param("owner") String owner);
    int markOutboxPublished(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                            @Param("owner") String owner, @Param("fencingToken") long fencingToken,
                            @Param("publishedAt") LocalDateTime publishedAt);
    int markOutboxRetry(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                        @Param("owner") String owner, @Param("fencingToken") long fencingToken,
                        @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("lastError") String lastError);
    int claimCallback(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                      @Param("callbackOwner") String callbackOwner, @Param("now") LocalDateTime now);
    int retryCallback(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                      @Param("callbackOwner") String callbackOwner, @Param("lastError") String lastError);
    int finishCallback(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                       @Param("taskId") String taskId, @Param("callbackOwner") String callbackOwner,
                       @Param("deliveredAt") LocalDateTime deliveredAt);
    List<SubagentTaskPO> queryExpiredExecutions(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int resetExpiredExecution(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                              @Param("fencingToken") long fencingToken,
                              @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    List<SubagentTaskPO> queryExpiredCallbacks(@Param("staleBefore") LocalDateTime staleBefore,
                                               @Param("limit") int limit);
    int resetExpiredCallback(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                             @Param("callbackOwner") String callbackOwner,
                             @Param("callbackClaimedAt") LocalDateTime callbackClaimedAt);
    int prepareCallbackReplay(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                              @Param("taskId") String taskId);
    int markCallbackDead(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                         @Param("taskId") String taskId);
}
