package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户 Agent 状态覆盖实体。
 */
@Data
@Builder
public class AgentTenantOverrideEntity {
    /** 覆盖所属租户。 */
    private String tenantId;
    /** 被覆盖的静态 Agent。 */
    private String agentId;
    /** active 或 disabled。 */
    private String status;
    /** 管理员填写的变更原因。 */
    private String reason;
    /** 执行变更的可信用户。 */
    private String updatedBy;
    /** 乐观锁版本。 */
    private Long revision;
    /** 禁用时间；启用时为空。 */
    private LocalDateTime disabledAt;
}
