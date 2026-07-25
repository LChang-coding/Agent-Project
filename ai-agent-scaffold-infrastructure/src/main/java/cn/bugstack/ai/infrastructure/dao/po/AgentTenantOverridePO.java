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
    /** 覆盖规则所属租户。 */
    private String tenantId;
    /** 被覆盖的平台 Agent。 */
    private String agentId;
    /** 租户内状态，如 enabled/disabled。 */
    private String status;
    /** 管理员停用或恢复原因。 */
    private String reason;
    /** 最近修改规则的用户。 */
    private String updatedBy;
    /** 乐观并发修订号。 */
    private Long revision;
    /** 最近一次停用生效时间。 */
    private LocalDateTime disabledAt;
}
