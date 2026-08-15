package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class ParentInboxItemPO {
    private Long sequence;
    private String tenantId;
    private String parentRunId;
    private String taskId;
    private String childAgentId;
    private String resultSummary;
    private String taskStatus;
    private String callbackStatus;
}
