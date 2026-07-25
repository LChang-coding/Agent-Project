package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 从配置协调出的唯一可领取运行时任务。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentScheduleTaskPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 任务归属用户ID，一般等于 run_as_user_id
     */
    private String userId;

    /**
     * 调度配置业务ID
     */
    private String configId;

    /**
     * 调度任务实例ID
     */
    private String taskId;

    /** 调度实例去重使用的业务键。 */
    private String businessKey;

    /** 当前任务内容对应的配置摘要。 */
    private String configHash;

    /** 当前任务同步到的配置版本。 */
    private Long configVersion;

    /** 计算后续计划时刻的 Cron 表达式快照。 */
    private String cronExpr;

    /** 解释 Cron 的时区快照。 */
    private String timezone;

    /** 错过计划时刻后的补偿策略。 */
    private String misfirePolicy;

    /** 单次计划发生允许的最大重试数。 */
    private Integer maxRetries;

    /**
     * 计划执行时间
     */
    private LocalDateTime plannedTime;

    /** 短周期扫描器下一次可领取时刻。 */
    private LocalDateTime nextFireTime;

    /** 已完成或永久失败的最近计划时刻。 */
    private LocalDateTime lastPlannedTime;

    /** 当前失败发生的下一次重试时刻。 */
    private LocalDateTime retryAt;

    /**
     * 任务状态：pending/running/success/failed/canceled
     */
    private String status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /** 当前领取实例；为空表示未被占用。 */
    private String leaseOwner;

    /** 当前租约到期时间。 */
    private LocalDateTime leaseUntil;

    /** 每次成功领取递增，隔离过期执行者。 */
    private Long fencingToken;

    /** 任务行乐观锁版本。 */
    private Long rowVersion;

    /**
     * 扩展信息
     */
    private String metadata;
}
