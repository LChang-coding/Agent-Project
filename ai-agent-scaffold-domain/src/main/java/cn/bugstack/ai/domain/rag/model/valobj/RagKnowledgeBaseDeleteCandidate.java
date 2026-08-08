package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库删除任务全局扫描返回的最小候选投影。
 * <p>候选不携带删除进度，也不表示任务已被领取。协调器必须使用 tenantId 与 taskId
 * 在租户范围内执行原子领取。</p>
 *
 * @param tenantId 任务所属租户
 * @param taskId 知识库删除任务标识
 */
public record RagKnowledgeBaseDeleteCandidate(String tenantId, String taskId) {

    /**
     * 构造校验：两个定位字段必须都有值，否则这个候选根本没法回查，留着只会让调度循环空转。
     */
    public RagKnowledgeBaseDeleteCandidate {
        // 租户或任务编号任一缺失，都意味着无法安全地回到租户范围内领取任务。
        if (tenantId == null || tenantId.isBlank() || taskId == null || taskId.isBlank()) {
      // 直接拒绝，把无效候选挡在调度逻辑之外。
         throw new IllegalArgumentException("知识库删除候选任务标识非法");
        }
    }
}
