package cn.bugstack.ai.domain.schedule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 同一计划触发点的逻辑执行记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleExecutionEntity {

    /** 执行所属租户。 */
    private String tenantId;
    /** 固化的执行用户。 */
    private String userId;
    /** 来源配置标识。 */
    private String configId;
    /** 来源运行态标识。 */
    private String taskId;
    /** 本次执行记录标识。 */
    private String executionId;
    /** 业务键、配置版本和计划时间组成的幂等键。 */
    private String triggerKey;
    /** 串联本次 Agent 调用日志的链路标识。 */
    private String traceId;
    /** 本次逻辑触发点的 UTC 时间。 */
    private LocalDateTime plannedTime;
    /** 当前触发点的尝试序号。 */
    private int attemptNo;
    /** 阻止过期 Worker 回写的栅栏令牌。 */
    private long fencingToken;
    /** 持有本次短租约的实例。 */
    private String leaseOwner;
    /** 实际开始时间。 */
    private LocalDateTime startTime;
    /** 实际结束时间。 */
    private LocalDateTime endTime;
    /** 处理器执行耗时。 */
    private Long durationMs;
    /** running、success、failed 或 dead。 */
    private String status;
    /** 截断后的可读失败原因。 */
    private String errorMessage;
    /** 处理器返回的结构化结果。 */
    private String resultJson;
}
