package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO;
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
}
