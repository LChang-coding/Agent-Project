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

}
