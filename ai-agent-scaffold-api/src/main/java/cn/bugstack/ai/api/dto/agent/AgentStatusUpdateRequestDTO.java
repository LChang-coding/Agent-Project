package cn.bugstack.ai.api.dto.agent;

import lombok.Data;

/** Agent 状态更新请求。 */
@Data
public class AgentStatusUpdateRequestDTO {
    private Boolean enabled;
    private String status;
    private String reason;
    private Long expectedRevision;
    private Long revision;
}
