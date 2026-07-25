package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteCandidatePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 知识库级联删除任务账本DAO。 */
@Mapper
public interface IRagKnowledgeBaseDeleteTaskDao {

    /** 创建知识库级联删除账本。 */
    int insert(RagKnowledgeBaseDeleteTaskPO task);

    /** 在租户范围内查询删除任务。 */
    RagKnowledgeBaseDeleteTaskPO queryByTenantAndTaskId(@Param("tenantId") String tenantId,
                                                        @Param("taskId") String taskId);

    /** 悲观锁定任务，串行化用户取消与状态推进。 */
    RagKnowledgeBaseDeleteTaskPO queryByTenantAndTaskIdForUpdate(@Param("tenantId") String tenantId,
                                                                 @Param("taskId") String taskId);

    /** 查询知识库当前删除任务，防止重复创建级联清理。 */
    RagKnowledgeBaseDeleteTaskPO queryByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                                 @Param("knowledgeBaseId") String knowledgeBaseId);

    /** 以期望修订号更新；返回 0 表示并发状态已变化。 */
    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("task") RagKnowledgeBaseDeleteTaskPO task,
                                  @Param("expectedRevision") long expectedRevision);

    /** 扫描到期且未被有效租约占用的候选任务。 */
    java.util.List<RagKnowledgeBaseDeleteCandidatePO> queryDueCandidates(
            @Param("now") java.time.LocalDateTime now, @Param("limit") int limit);

    /** 原子领取到期任务并递增 fencing token。 */
    int claimDue(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                 @Param("leaseOwner") String leaseOwner, @Param("now") java.time.LocalDateTime now,
                 @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    /** 仅允许当前持有者和 fencing token 续租。 */
    int heartbeatClaimed(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("fencingToken") long fencingToken,
                         @Param("now") java.time.LocalDateTime now,
                         @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    /** 同时校验租约、围栏令牌和修订号后推进任务。 */
    int updateClaimedByTenantFenceAndRevision(
            @Param("tenantId") String tenantId,
            @Param("task") RagKnowledgeBaseDeleteTaskPO task,
            @Param("expectedRevision") long expectedRevision,
            @Param("leaseOwner") String leaseOwner,
            @Param("fencingToken") long fencingToken,
            @Param("now") java.time.LocalDateTime now);
}
