package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

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

    /** 按租户和幂等任务键查询。 */
    RagIngestTaskPO queryByTenantAndTaskKey(@Param("tenantId") String tenantId,
                                           @Param("taskKey") String taskKey);

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
}
