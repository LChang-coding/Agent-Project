package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteCandidatePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 知识库级联删除任务账本DAO。 */
@Mapper
public interface IRagKnowledgeBaseDeleteTaskDao {

    int insert(RagKnowledgeBaseDeleteTaskPO task);

    RagKnowledgeBaseDeleteTaskPO queryByTenantAndTaskId(@Param("tenantId") String tenantId,
                                                        @Param("taskId") String taskId);

    RagKnowledgeBaseDeleteTaskPO queryByTenantAndKnowledgeBaseId(@Param("tenantId") String tenantId,
                                                                 @Param("knowledgeBaseId") String knowledgeBaseId);

    int updateByTenantAndRevision(@Param("tenantId") String tenantId,
                                  @Param("task") RagKnowledgeBaseDeleteTaskPO task,
                                  @Param("expectedRevision") long expectedRevision);

    java.util.List<RagKnowledgeBaseDeleteCandidatePO> queryDueCandidates(
            @Param("now") java.time.LocalDateTime now, @Param("limit") int limit);

    int claimDue(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                 @Param("leaseOwner") String leaseOwner, @Param("now") java.time.LocalDateTime now,
                 @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    int heartbeatClaimed(@Param("tenantId") String tenantId, @Param("taskId") String taskId,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("fencingToken") long fencingToken,
                         @Param("now") java.time.LocalDateTime now,
                         @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    int updateClaimedByTenantFenceAndRevision(
            @Param("tenantId") String tenantId,
            @Param("task") RagKnowledgeBaseDeleteTaskPO task,
            @Param("expectedRevision") long expectedRevision,
            @Param("leaseOwner") String leaseOwner,
            @Param("fencingToken") long fencingToken,
            @Param("now") java.time.LocalDateTime now);
}
