package cn.bugstack.ai.api.dto;

import lombok.Data;

/**
 * 智能体配置响应对象
 *
 * 2026/1/20 08:18
 */
@Data
public class AiAgentConfigResponseDTO {

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /** 当前租户状态。 */
    private String status;

    /** 配置事实源。 */
    private String sourceType;

    /** 当前身份是否可管理。 */
    private Boolean manageable;

    /** 当前租户是否可运行。 */
    private Boolean enabled;

    /** 状态覆盖乐观锁版本。 */
    private Long revision;

    /** 禁用时间。 */
    private String disabledAt;

}
