package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * Worker 全局扫描到期任务时拿到的「只有身份、没有内容」的候选条目。
 *
 * <p>属于哪一层：领域层值对象，不可变。</p>
 *
 * <p>为什么要有这么一个瘦对象：扫描是跨租户的全局操作，如果直接把任务全文捞出来，
 * 一次扫描就等于把所有租户的业务数据装进一个 Worker 的内存，既浪费又扩大了泄露面。
 * 所以扫描只返回「哪个租户的哪个任务到期了」，Worker 拿到之后必须再用
 * tenantId + jobId 回到租户范围内做一次原子领取，真正的数据是在那一步才读出来的。</p>
 *
 * <p>谁产出它：IRagRepository.listDueIngestJobCandidates（那是整个仓储里唯一不以 tenantId 开头的方法）。
 * 谁消费它：摄取 Worker 的调度循环。</p>
 *
 * <p>它不负责什么：不代表任务已经归你了。多个 Worker 可能扫到同一个候选，
 * 谁能真正执行由后续的原子领取（claim）决定，这里没有任何独占语义。</p>
 *
 * @param tenantId 任务所属租户；回查和领取都必须带上它，否则就成了跨租户操作。
 *             它是把全局扫描重新收敛回租户隔离的关键。
 * @param jobId 任务编号；与 tenantId 组成回查坐标。单独一个 jobId 不允许用来查询或修改。
 */
public record RagIngestJobCandidate(String tenantId, String jobId) {

    /**
     * 构造校验：两个身份字段都必须有值。
     *
     * <p>缺任何一个，这个候选就无法回查，Worker 拿到只能丢弃；提前拒绝比让调度循环
     * 反复处理无效条目更安全。</p>
     */
    public RagIngestJobCandidate {
        // 租户缺失会让后续领取失去隔离依据，直接拒绝。
     requireText(tenantId, "tenantId");
      // 任务编号缺失则无法定位到具体任务，同样拒绝。
        requireText(jobId, "jobId");
    }

 /**
     * 校验候选身份字段非空。
     *
   * <p>这里刻意不做任何格式猜测，只保证「有值」——候选对象的唯一职责就是能被回查，
   * 格式合法性由真正的领取和查询去判断。</p>
     */
  private static String requireText(String value, String fieldName) {
        // 空串和纯空白与 null 等价，都无法用于定位任务。
        if (value == null || value.isBlank()) {
            // 带上字段名抛出，方便一眼看出是哪个身份字段缺失。
       throw new IllegalArgumentException(fieldName + "不能为空");
        }
// 校验通过后原样返回，便于在构造里链式使用。
        return value;
    }
}
