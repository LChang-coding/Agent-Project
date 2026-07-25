package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 单个计划发生的幂等执行账本。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentScheduleExecutionPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 执行归属用户ID，一般等于任务 user_id
     */
    private String userId;

    /**
     * 调度任务实例ID
     */
    private String taskId;

    /** 产生本次执行的配置 ID。 */
    private String configId;

    /**
     * 执行记录ID
     */
    private String executionId;

    /** 配置与计划时刻构成的幂等键。 */
    private String triggerKey;

    /**
     * 链路ID
     */
    private String traceId;

    /** 本次执行对应的 Cron 计划时刻。 */
    private LocalDateTime plannedTime;

    /** 当前计划发生的尝试序号。 */
    private Integer attemptNo;

    /** 领取任务时冻结的围栏令牌。 */
    private Long fencingToken;

    /** 获得本次执行权的实例标识。 */
    private String leaseOwner;

    /**
     * 执行开始时间
     */
    private LocalDateTime startTime;

    /**
     * 执行结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行耗时，单位毫秒
     */
    private Long durationMs;

    /**
     * 执行状态：running/success/failed/canceled
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /** 目标返回结果的受限 JSON 快照。 */
    private String resultJson;

    /**
     * 扩展信息
     */
    private String metadata;
}
