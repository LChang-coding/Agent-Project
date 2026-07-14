package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatMessageEntity {

    private String tenantId;

    private String userId;

    private String sessionId;

    private String messageId;

    private String runId;

    private String validityStatus;

    private String invalidReason;

    private java.time.LocalDateTime invalidatedAt;

    private String role;

    private String contentType;

    private String content;

    /**
     * 上下文 token 预估值。
     */
    private Integer estimatedTokenCount;

    private Integer sequenceNo;

    private String parentMessageId;

    private String traceId;
}
