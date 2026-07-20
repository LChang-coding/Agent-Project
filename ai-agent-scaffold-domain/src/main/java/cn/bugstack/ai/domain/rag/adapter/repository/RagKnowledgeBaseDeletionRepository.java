package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;

import java.util.Optional;

/** 知识库级联删除任务账本和聚合事务端口。 */
public interface RagKnowledgeBaseDeletionRepository {

    Optional<RagKnowledgeBaseDeleteTaskEntity> findByTaskId(String tenantId, String taskId);

    Optional<RagKnowledgeBaseDeleteTaskEntity> findByKnowledgeBaseId(String tenantId,
                                                                      String knowledgeBaseId);

    /** 锁定聚合后建立删除屏障；重复唯一键返回false。 */
    boolean register(String tenantId, RagKnowledgeBaseDeleteRegistration registration);

    int update(String tenantId, RagKnowledgeBaseDeleteTaskEntity task, long expectedRevision);
}
