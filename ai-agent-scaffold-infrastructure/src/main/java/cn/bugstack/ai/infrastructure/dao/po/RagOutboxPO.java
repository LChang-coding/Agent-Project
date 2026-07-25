package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 事务 Outbox 持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagOutboxPO extends BasePO {
    /** 事件业务 ID，也是发布幂等键。 */
    private String eventId;
    /** 事件所属租户。 */
    private String tenantId;
    /** 关联摄取或删除任务。 */
    private String taskId;
    /** 产生事件的聚合类型。 */
    private String aggregateType;
    /** 产生事件的聚合业务 ID。 */
    private String aggregateId;
    /** 领域事件类型。 */
    private String eventType;
    /** 目标 Kafka Topic。 */
    private String topicName;
    /** 保证同聚合有序的分区键。 */
    private String partitionKey;
    /** 事件负载 JSON。 */
    private String payload;
    /** 事件创建链路 ID。 */
    private String traceId;
    /** pending/publishing/retry/published/dead 状态。 */
    private String status;
    /** 已领取发布次数。 */
    private Integer attemptCount;
    /** 最大发布次数。 */
    private Integer maxAttempts;
    /** 失败后下次可领取时间。 */
    private LocalDateTime nextRetryAt;
    /** 当前发布实例。 */
    private String leaseOwner;
    /** 当前发布租约到期时间。 */
    private LocalDateTime leaseUntil;
    /** 发布者最近续租时间。 */
    private LocalDateTime heartbeatAt;
    /** 每次领取递增的围栏令牌。 */
    private Long fencingToken;
    /** 行级乐观锁版本。 */
    private Long rowVersion;
    /** 最近发布失败摘要。 */
    private String errorMessage;
    /** Broker 确认发布时间。 */
    private LocalDateTime publishedAt;
}
