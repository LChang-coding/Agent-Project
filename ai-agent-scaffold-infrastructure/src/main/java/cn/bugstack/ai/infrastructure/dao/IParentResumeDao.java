package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.infrastructure.dao.po.ParentInboxItemPO;
import cn.bugstack.ai.infrastructure.dao.po.ParentResumeRequestPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface IParentResumeDao {
    int markCallbackRegistered(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                               @Param("callbackOwner") String callbackOwner);
    int insertInbox(ParentInboxItemPO item);
    int upsertRequest(ParentResumeRequestPO request);
    int insertOutbox(AgentOrchestrationOutboxPO event);
    int claim(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
              @Param("workerId") String workerId, @Param("now") LocalDateTime now,
              @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    ParentResumeRequestPO queryOwned(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                                     @Param("workerId") String workerId);
    List<ParentInboxItemPO> queryInbox(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                                      @Param("cursor") long cursor, @Param("limit") int limit);
    int renewLease(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                   @Param("workerId") String workerId, @Param("fencingToken") long fencingToken,
                   @Param("now") LocalDateTime now, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    int markInboxConsumed(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                          @Param("taskIds") List<String> taskIds, @Param("deliveredAt") LocalDateTime deliveredAt);
    int finishTaskDelivery(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                           @Param("taskId") String taskId, @Param("deliveredAt") LocalDateTime deliveredAt);
    int completeRequest(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                        @Param("workerId") String workerId, @Param("fencingToken") long fencingToken,
                        @Param("requestedVersion") long requestedVersion, @Param("cursor") long cursor,
                        @Param("deliveredAt") LocalDateTime deliveredAt);
    ParentResumeRequestPO query(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId);
    int retry(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
              @Param("workerId") String workerId, @Param("fencingToken") long fencingToken,
              @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("lastError") String lastError);
    List<ParentResumeRequestPO> queryRecoveryCandidates(@Param("now") LocalDateTime now,
                                                        @Param("staleBefore") LocalDateTime staleBefore,
                                                        @Param("limit") int limit);
    int markRecoveryNotified(@Param("tenantId") String tenantId, @Param("parentRunId") String parentRunId,
                             @Param("status") String status, @Param("fencingToken") long fencingToken,
                             @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);
}
