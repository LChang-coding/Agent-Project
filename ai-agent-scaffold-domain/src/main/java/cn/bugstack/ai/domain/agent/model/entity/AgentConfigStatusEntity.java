package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 带租户状态的静态 Agent 配置摘要。
 */
@Data
@Builder
public class AgentConfigStatusEntity {
    /** 静态 Agent 标识。 */
    private String agentId;
    /** 静态展示名。 */
    private String agentName;
    /** 静态描述。 */
    private String agentDesc;
    /** 合并覆盖后的 enabled/disabled 状态。 */
    private String status;
    /** 供接口直接判断的可运行标记。 */
    private Boolean enabled;
    /** 租户覆盖 revision；无覆盖为零。 */
    private Long revision;
    /** 最近禁用时间。 */
    private LocalDateTime disabledAt;
}
