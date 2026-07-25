package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户在租户内的角色、状态和加入时间。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TenantUserPO extends BasePO {

    /**
     * 租户业务ID
     */
    private String tenantId;

    /**
     * 用户业务ID
     */
    private String userId;

    /**
     * 租户角色：owner/admin/developer/member
     */
    private String roleCode;

    /**
     * 关系状态：active/disabled
     */
    private String status;

    /**
     * 加入租户时间
     */
    private LocalDateTime joinedTime;

    /**
     * 扩展信息
     */
    private String metadata;
}
