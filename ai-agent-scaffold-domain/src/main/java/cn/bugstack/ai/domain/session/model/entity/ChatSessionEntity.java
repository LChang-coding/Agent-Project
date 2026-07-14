package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionEntity {

    private String tenantId;

    private String userId;

    private String sessionId;

    private String agentId;

    private String agentName;

    private String appName;

    private String title;

    private String status;

    private LocalDateTime lastMessageTime;

    /**
     * 有效上下文版本。
     */
    private Long contextRevision;
}
