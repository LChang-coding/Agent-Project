package cn.bugstack.ai.api.dto.schedule;

import lombok.Data;

/**
 * 创建或修改定时 Agent 消息的请求。
 */
@Data
public class ScheduleSaveRequestDTO {
    private String configId;
    private String agentId;
    private String agentName;
    private String message;
    private String cronExpr;
    private String timezone;
    private Boolean enabled;
    private String misfirePolicy;
    private Integer maxRetries;
}
