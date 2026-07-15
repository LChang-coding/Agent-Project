package cn.bugstack.ai.api.dto.session;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话摘要响应。
 */
@Data
@Builder
public class SessionSummaryResponseDTO {
    private String sessionId;
    private String agentId;
    private String agentName;
    private String appName;
    private String title;
    private String status;
    private LocalDateTime lastMessageTime;
    private Long contextRevision;
}
