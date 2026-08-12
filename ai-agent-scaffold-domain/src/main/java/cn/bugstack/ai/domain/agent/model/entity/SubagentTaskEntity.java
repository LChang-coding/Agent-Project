package cn.bugstack.ai.domain.agent.model.entity;

import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次临时子 Agent 执行的权威任务记录。 */
@Data
@Builder
public class SubagentTaskEntity {
    private String tenantId;
    private String userId;
    private String parentRunId;
    private String parentSessionId;
    private String parentAgentId;
    private String taskId;
    private String childAgentId;
    private String childSessionId;
    private String instruction;
    private String functionCallId;
    private String traceId;
    private SubagentTaskStatus status;
    private Integer attempt;
    private Long fencingToken;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    /** 兼容旧消费者的最终回答正文；新主 Agent 唤醒只使用 resultSummary。 */
    private String resultText;
    /** 自动注入主 Agent 的有界结果摘要，最大 1000 个字符。 */
    private String resultSummary;
    /** 子 Agent 本次返回的完整内容，只能通过受信平台工具按父运行范围读取。 */
    private String fullContext;
    /** 摘要是否因长度上限被截断。 */
    private Boolean summaryTruncated;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime acknowledgedAt;
    private String callbackStatus;
    private String callbackOwner;
    private LocalDateTime callbackClaimedAt;
    private Integer callbackAttempt;
}
