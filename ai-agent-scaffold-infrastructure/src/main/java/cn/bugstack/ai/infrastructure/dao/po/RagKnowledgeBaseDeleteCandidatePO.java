package cn.bugstack.ai.infrastructure.dao.po;

/** 知识库删除到期扫描投影：只携带领取所需的 tenantId 与 taskId。 */
public record RagKnowledgeBaseDeleteCandidatePO(String tenantId, String taskId) {
}
