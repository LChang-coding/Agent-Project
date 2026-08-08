package cn.bugstack.ai.infrastructure.rag.outbox;

import cn.bugstack.ai.infrastructure.dao.IRagOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxCandidatePO;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** Outbox 短事务领取服务。 */
@Service
@RequiredArgsConstructor
public class RagOutboxClaimService {

    /** outbox 状态事实源。 */
    private final IRagOutboxDao outboxDao;

    /** 按租户和事件原子领取，并在同一短事务内读回新栅栏。 */
    @Transactional(rollbackFor = Exception.class)
    /** 按 tenantId + eventId 原子领取，并回读新 fencing token。 */
    public Optional<RagOutboxPO> claim(RagOutboxCandidatePO candidate, String leaseOwner,
                                       LocalDateTime now, LocalDateTime leaseUntil) {
        if (candidate == null || blank(candidate.getTenantId()) || blank(candidate.getEventId())) {
            return Optional.empty();
        }
        int claimed = outboxDao.claimDue(candidate.getTenantId(), candidate.getEventId(),
                leaseOwner, now, leaseUntil);
        if (claimed != 1) return Optional.empty();
        return Optional.ofNullable(outboxDao.queryByTenantAndEventId(
                candidate.getTenantId(), candidate.getEventId()));
    }

    /** 判断候选的租户或事件标识是否不可用于领取。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
