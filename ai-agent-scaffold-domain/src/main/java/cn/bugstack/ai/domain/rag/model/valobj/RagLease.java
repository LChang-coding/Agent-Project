package cn.bugstack.ai.domain.rag.model.valobj;

import java.time.Instant;

/**
 * 后台任务的有时限执行归属。
 * <p>任务领取时创建租约，正在执行的实例通过续租延长到期时间。
 * 租约到期后，其他实例可以重新领取任务。已过期执行实例的写入还需由单调递增的
 * fencing token 拒绝。</p>
 *
 * @param owner 持有租约的执行实例标识
 * @param expiresAt 租约到期时刻
 */
public record RagLease(String owner, Instant expiresAt) {

    /** 校验执行实例标识和租约到期时刻均已提供。 */
  public RagLease {
        if (owner == null || owner.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("摄取租约参数不完整");
      }
    }

    /**
     * 判断租约在指定时刻是否已到期。
     * <p>当 {@code expiresAt} 等于 {@code now} 时也视为到期，以保证新执行实例
     * 可以在明确的时间边界重新领取。</p>
     *
     * @param now 租约有效性校验时刻
     * @return 租约已到期时返回 {@code true}
     */
    public boolean expiredAt(Instant now) {
        if (now == null) {
  throw new IllegalArgumentException("租约校验时间不能为空");
        }
        return !expiresAt.isAfter(now);
  }
}
