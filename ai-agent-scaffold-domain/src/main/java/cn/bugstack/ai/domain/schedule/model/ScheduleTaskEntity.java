package cn.bugstack.ai.domain.schedule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一份配置对应的调度运行态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleTaskEntity {

    /** 任务所属租户。 */
    private String tenantId;
    /** 固化的执行用户。 */
    private String userId;
    /** 唯一来源配置。 */
    private String configId;
    /** 运行态稳定标识。 */
    private String taskId;
    /** 租户与配置计算出的幂等业务键。 */
    private String businessKey;
    /** 当前已收敛配置的内容摘要。 */
    private String configHash;
    /** 当前已收敛配置版本。 */
    private long configVersion;
    /** 已规范化的 Cron 表达式。 */
    private String cronExpr;
    /** Cron 解释时区。 */
    private String timezone;
    /** 错过触发点时的推进策略。 */
    private String misfirePolicy;
    /** 当前触发点最大重试次数。 */
    private int maxRetries;
    /** 初次生成运行态时计算的计划时间。 */
    private LocalDateTime plannedTime;
    /** 扫描器判断到期的权威时间。 */
    private LocalDateTime nextFireTime;
    /** 最近完成或放弃的逻辑触发点。 */
    private LocalDateTime lastPlannedTime;
    /** 当前触发点失败后的最早重试时间。 */
    private LocalDateTime retryAt;
    /** ready、running 或 disabled。 */
    private String status;
    /** 当前触发点已经失败的次数。 */
    private int retryCount;
    /** 当前抢占实例与执行批次标识。 */
    private String leaseOwner;
    /** 租约失效时间。 */
    private LocalDateTime leaseUntil;
    /** 每次抢占递增，隔离陈旧 Worker。 */
    private long fencingToken;
    /** 数据库乐观锁版本。 */
    private long rowVersion;
}
