package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库删除协调器全局扫描时拿到的「只有身份」候选条目。
 *
 * <p>属于哪一层：领域层值对象，不可变。作用与摄取侧的 RagIngestJobCandidate 完全对称：
 * 全局扫描只允许返回定位坐标，不允许把租户的业务正文和进度带出租户边界。</p>
 *
 * <p>谁产出它：RagKnowledgeBaseDeletionRepository.listDueCandidates。
 * 谁消费它：知识库删除协调器，拿到后必须再用 tenantId + taskId 做原子领取。</p>
 *
 * <p>它不负责什么：不携带 checkpoint，所以扫描阶段完全不知道删到第几个文档了；
 * 也不代表任务已被独占，独占由 claim 签发栅栏时才成立。</p>
 *
 * @param tenantId 任务所属租户；后续领取和更新都靠它把操作重新限制在单租户内。
 * @param taskId 删除任务编号；与 tenantId 组成回查坐标。
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
