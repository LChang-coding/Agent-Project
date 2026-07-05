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

    private String role;

    private String contentType;

    private String content;

    private Integer sequenceNo;

    private String parentMessageId;

    private String traceId;
}
