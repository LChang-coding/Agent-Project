package cn.bugstack.ai.api.dto.session;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息响应。
 */
@Data
@Builder
public class SessionMessageResponseDTO {
    private String messageId;
    private String runId;
    private String role;
    private String contentType;
    private String content;
    private Integer estimatedTokenCount;
    private Integer sequenceNo;
    private LocalDateTime createTime;
}
