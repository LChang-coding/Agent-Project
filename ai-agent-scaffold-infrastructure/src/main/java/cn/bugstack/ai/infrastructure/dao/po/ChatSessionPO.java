package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatSessionPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 会话归属用户ID
     */
    private String userId;

    /**
     * 会话业务ID
     */
    private String sessionId;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent 名称
     */
    private String agentName;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 会话状态：active/archived/deleted
     */
    private String status;

    /**
     * 最后消息时间
     */
    private LocalDateTime lastMessageTime;

    /**
     * 扩展信息
     */
    private String metadata;
}
