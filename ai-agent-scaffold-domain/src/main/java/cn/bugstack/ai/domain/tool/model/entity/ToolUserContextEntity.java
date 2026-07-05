package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具操作用户上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolUserContextEntity {

    /**
     * 租户业务ID
     */
    private String tenantId;

    /**
     * 用户业务ID
     */
    private String userId;

    /**
     * 租户角色编码
     */
    private String roleCode;
}
