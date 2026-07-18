package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * Outbox 全局到期扫描标识。
 * <p>全局扫描只允许返回事件和租户标识，不携带业务载荷。</p>
 */
@Data
public class RagOutboxCandidatePO {
    private String eventId;
    private String tenantId;
}
