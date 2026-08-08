package cn.bugstack.ai.domain.context.model;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文压缩执行账本。
 */
@Data
@Builder
public class ContextCompactionTaskEntity {

    /** 压缩任务业务标识。 */
    private String taskId;
    /** 覆盖范围与基线组成的幂等键。 */
    private String taskKey;
    /** 任务所属租户。 */
    private String tenantId;
    /** 任务所属用户。 */
    private String userId;
    /** 任务所属会话。 */
    private String sessionId;
    /** 触发该任务的运行。 */
    private String runId;
    /** 本次压缩范围起始序号。 */
    private Integer fromSequence;
    /** 本次压缩范围结束序号。 */
    private Integer toSequence;
    /** 创建任务时的长期摘要版本。 */
    private Integer expectedMemoryVersion;
    /** 创建任务时的会话上下文版本。 */
    private Long baseContextRevision;
    /** 压缩范围内有效消息的内容摘要。 */
    private String coverageHash;
    /** 创建任务时的压缩策略版本。 */
    private String policyVersion;
    /** 持久化任务状态。 */
    private ContextCompactionTaskStatus status;
    /** 已领取或重试次数。 */
    private Integer attemptCount;
    /** 当前处理租约所有者。 */
    private String leaseOwner;
    /** 当前处理租约到期时间。 */
    private java.time.LocalDateTime leaseUntil;
    /** 拒绝陈旧消费者提交的栅栏令牌。 */
    private Long fencingToken;
    /** 最近一次失败摘要。 */
    private String errorMessage;
    /** 原始触发链路标识。 */
    private String traceId;

    /**
     * 转为 Kafka 命令；返回异步压缩通知。
     */
    public ContextCompactionCommand toCommand() {
        // 消息只携带定位信息；状态、覆盖指纹、租约和栅栏必须从 MySQL 重新读取。
        return new ContextCompactionCommand(taskId, tenantId, userId, sessionId, fromSequence, toSequence,
                expectedMemoryVersion, policyVersion, traceId);
    }
}
