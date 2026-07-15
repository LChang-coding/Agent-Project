package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/** 租户 Agent 状态覆盖持久化对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentTenantOverridePO extends BasePO {
    private String tenantId;
    private String agentId;
    private String status;
    private String reason;
    private String updatedBy;
    private Long revision;
    private LocalDateTime disabledAt;
}
