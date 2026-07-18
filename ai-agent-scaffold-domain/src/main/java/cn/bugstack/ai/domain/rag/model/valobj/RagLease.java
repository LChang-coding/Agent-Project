package cn.bugstack.ai.domain.rag.model.valobj;

import java.time.Instant;

/**
 * 摄取 Worker 租约。
 *
 * @param owner 租约持有者
 * @param expiresAt 租约失效时间
 */
public record RagLease(String owner, Instant expiresAt) {

    public RagLease {
        if (owner == null || owner.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("摄取租约参数不完整");
        }
    }

    /** 判断给定时刻租约是否已经失效。 */
    public boolean expiredAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("租约校验时间不能为空");
        }
        return !expiresAt.isAfter(now);
    }
}
