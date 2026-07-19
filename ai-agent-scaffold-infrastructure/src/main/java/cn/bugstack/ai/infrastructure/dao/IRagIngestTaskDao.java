package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagIngestCandidatePO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户 RAG 摄取任务账本 DAO。
 */
@Mapper
public interface IRagIngestTaskDao {
    /** 新增摄取任务。 */
    int insert(RagIngestTaskPO task);

    /** 按租户和任务 ID 查询。 */
    RagIngestTaskPO queryByTenantAndTaskId(@Param("tenantId") String tenantId,
                                          @Param("taskId") String taskId);

    /** 按租户和知识库查询最新摄取任务。 */
    List<RagIngestTaskPO> queryListByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                              @Param("knowledgeBaseId") String knowledgeBaseId,
                                                              @Param("limit") int limit);

    /** 按租户和幂等任务键查询。 */
    RagIngestTaskPO queryByTenantAndTaskKey(@Param("tenantId") String tenantId,
                                           @Param("taskKey") String taskKey);

    /** 查询文档当前未闭合的任务，用于删除登记互斥。 */
    RagIngestTaskPO queryActiveByTenantAndDocumentId(@Param("tenantId") String tenantId,
                                                     @Param("documentId") String documentId);

    /** 全局扫描到期任务，只投影 tenantId + jobId。 */
    List<RagIngestCandidatePO> queryDueCandidates(@Param("now") LocalDateTime now,
                                                  @Param("limit") int limit);

    /** 按租户、任务 ID 和 revision 更新任务完整状态。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("task") RagIngestTaskPO task,
                                  @Param("expectedRevision") long expectedRevision);

    /** 原子领取指定租户任务并递增尝试次数、栅栏令牌和行版本。 */
    int claimDue(@Param("tenantId") String tenantId,
                 @Param("taskId") String taskId,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 为无租约或租约过期的 cancel_requested 任务分配清理租约。 */
    int claimCancelledForCleanup(@Param("tenantId") String tenantId,
                                  @Param("taskId") String taskId,
                                  @Param("leaseOwner") String leaseOwner,
                                  @Param("now") LocalDateTime now,
                                  @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 已领取 Worker 使用当前租约、栅栏令牌和行版本更新状态。 */
    int updateClaimedByTenantFenceAndRevision(@Param("tenantId") String tenantId,
                                               @Param("task") RagIngestTaskPO task,
                                               @Param("expectedRevision") long expectedRevision,
                                               @Param("leaseOwner") String leaseOwner,
                                               @Param("expectedFencingToken") long expectedFencingToken,
                                               @Param("now") LocalDateTime now);

    /** 独立心跳续租，不争用 row_version。 */
    int heartbeatClaimed(@Param("tenantId") String tenantId,
                         @Param("taskId") String taskId,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("expectedFencingToken") long expectedFencingToken,
                         @Param("now") LocalDateTime now,
                         @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 取消屏障生效后关闭任务；允许当前状态为 cancel_requested。 */
    int cancelClaimedByTenantFenceAndRevision(@Param("tenantId") String tenantId,
                                               @Param("task") RagIngestTaskPO task,
                                               @Param("expectedRevision") long expectedRevision,
                                               @Param("leaseOwner") String leaseOwner,
                                               @Param("expectedFencingToken") long expectedFencingToken);
}
