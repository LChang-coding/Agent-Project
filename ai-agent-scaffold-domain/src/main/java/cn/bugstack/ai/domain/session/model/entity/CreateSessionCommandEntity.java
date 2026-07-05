package cn.bugstack.ai.domain.session.model.entity;

import lombok.Data;

@Data
public class CreateSessionCommandEntity {

    private String tenantId;

    private String userId;

    private String sessionId;

    private String agentId;

    private String agentName;

    private String appName;

    private String title;
}
