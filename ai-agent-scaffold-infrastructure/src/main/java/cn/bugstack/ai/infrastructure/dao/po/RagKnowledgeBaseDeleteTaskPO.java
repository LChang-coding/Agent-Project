package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 知识库级联删除任务持久化对象。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagKnowledgeBaseDeleteTaskPO extends BasePO {
    private String taskId;
    private String taskKey;
    private String tenantId;
    private String knowledgeBaseId;
    private String status;
    private String checkpoint;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private Long fencingToken;
    private Long rowVersion;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
