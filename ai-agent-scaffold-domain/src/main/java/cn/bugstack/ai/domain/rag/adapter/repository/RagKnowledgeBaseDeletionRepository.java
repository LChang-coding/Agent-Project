package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 知识库级联删除任务账本和聚合事务端口。 */
public interface RagKnowledgeBaseDeletionRepository {

    /** 在租户范围内按任务标识读取删除账本。 */
    Optional<RagKnowledgeBaseDeleteTaskEntity> findByTaskId(String tenantId, String taskId);

    /** 查询知识库当前唯一未闭环删除任务。 */
    Optional<RagKnowledgeBaseDeleteTaskEntity> findByKnowledgeBaseId(String tenantId,
                                                                      String knowledgeBaseId);

    /** 锁定聚合后建立删除屏障；重复唯一键返回false。 */
    boolean register(String tenantId, RagKnowledgeBaseDeleteRegistration registration);

    /** 按 revision 更新尚未领取的删除任务。 */
    int update(String tenantId, RagKnowledgeBaseDeleteTaskEntity task, long expectedRevision);

    /** 全局扫描到期候选的最小投影，不返回租户业务正文。 */
    List<RagKnowledgeBaseDeleteCandidate> listDueCandidates(Instant now, int limit);

    /** 在指定租户内原子领取删除任务并签发栅栏。 */
    Optional<RagKnowledgeBaseDeleteTaskEntity> claim(String tenantId, String taskId,
                                                       String leaseOwner, Instant now,
                                                       Instant leaseUntil);

    /** 当前栅栏持有者续租，不改变业务 revision。 */
    int heartbeat(String tenantId, String taskId, String leaseOwner, long fencingToken,
                  Instant now, Instant leaseUntil);

    /** 同时校验 revision、租约和栅栏更新删除进度。 */
    int updateClaimed(String tenantId, RagKnowledgeBaseDeleteTaskEntity task,
                      long expectedRevision, String leaseOwner, long fencingToken, Instant now);

    /** 在零残留验证后同一事务关闭知识库和删除任务。 */
    void completeClaimed(String tenantId, String taskId, long expectedTaskRevision,
                         String leaseOwner, long fencingToken, Instant now);
}
