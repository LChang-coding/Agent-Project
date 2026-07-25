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
    /** 级联删除任务 ID。 */
    private String taskId;
    /** 同一知识库删除请求的幂等键。 */
    private String taskKey;
    /** 任务所属租户。 */
    private String tenantId;
    /** 待永久删除知识库。 */
    private String knowledgeBaseId;
    /** 发起危险操作的可信用户。 */
    private String requestedByUserId;
    /** pending/running/retry/终态。 */
    private String status;
    /** 已完成的级联清理阶段。 */
    private String checkpoint;
    /** 已领取执行次数。 */
    private Integer attemptCount;
    /** 最大执行次数。 */
    private Integer maxAttempts;
    /** 下次可领取时间。 */
    private LocalDateTime nextRetryAt;
    /** 当前清理 Worker。 */
    private String leaseOwner;
    /** 当前租约到期时间。 */
    private LocalDateTime leaseUntil;
    /** 最近续租心跳时间。 */
    private LocalDateTime heartbeatAt;
    /** 每次领取递增的围栏令牌。 */
    private Long fencingToken;
    /** 行级乐观锁版本。 */
    private Long rowVersion;
    /** 稳定机器可读错误码。 */
    private String errorCode;
    /** 受限的人类可读错误摘要。 */
    private String errorMessage;
    /** 首次实际执行时间。 */
    private LocalDateTime startedAt;
    /** 任务进入终态时间。 */
    private LocalDateTime finishedAt;
}
