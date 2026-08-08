package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 全局到期任务扫描返回的最小候选投影。
 * <p>候选只用于定位租户范围内的任务，不表示任务已被领取。执行实例必须后续使用
 * tenantId 与 jobId 执行原子领取。</p>
 *
 * @param tenantId 任务所属租户
 * @param jobId 摄取任务标识
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
