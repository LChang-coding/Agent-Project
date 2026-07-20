package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 知识库级联删除任务账本和聚合事务端口。 */
public interface RagKnowledgeBaseDeletionRepository {

    Optional<RagKnowledgeBaseDeleteTaskEntity> findByTaskId(String tenantId, String taskId);

    Optional<RagKnowledgeBaseDeleteTaskEntity> findByKnowledgeBaseId(String tenantId,
                                                                      String knowledgeBaseId);

    /** 锁定聚合后建立删除屏障；重复唯一键返回false。 */
    boolean register(String tenantId, RagKnowledgeBaseDeleteRegistration registration);

    int update(String tenantId, RagKnowledgeBaseDeleteTaskEntity task, long expectedRevision);

    List<RagKnowledgeBaseDeleteCandidate> listDueCandidates(Instant now, int limit);

    Optional<RagKnowledgeBaseDeleteTaskEntity> claim(String tenantId, String taskId,
                                                       String leaseOwner, Instant now,
                                                       Instant leaseUntil);

    int heartbeat(String tenantId, String taskId, String leaseOwner, long fencingToken,
                  Instant now, Instant leaseUntil);

    int updateClaimed(String tenantId, RagKnowledgeBaseDeleteTaskEntity task,
                      long expectedRevision, String leaseOwner, long fencingToken, Instant now);

    /** 在零残留验证后同一事务关闭知识库和删除任务。 */
    void completeClaimed(String tenantId, String taskId, long expectedTaskRevision,
                         String leaseOwner, long fencingToken, Instant now);
}
