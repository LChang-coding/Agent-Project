package cn.bugstack.ai.domain.rag.model.valobj;

/** 删除协调器扫描的轻量标识，不携带checkpoint。 */
public record RagKnowledgeBaseDeleteCandidate(String tenantId, String taskId) {
    public RagKnowledgeBaseDeleteCandidate {
        if (tenantId == null || tenantId.isBlank() || taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("知识库删除候选任务标识非法");
        }
    }
}
