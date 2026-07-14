package cn.bugstack.ai.domain.session.model.entity;

import lombok.Data;

@Data
public class AppendMessageCommandEntity {

    private String tenantId;

    private String userId;

    private String sessionId;

    private String runId;

    private String role;

    private String contentType;

    private String content;

    private String parentMessageId;

    private String traceId;
}
