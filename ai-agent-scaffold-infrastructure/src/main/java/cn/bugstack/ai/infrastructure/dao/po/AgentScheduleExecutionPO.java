package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    /**
     * 执行记录ID
     */
    private String executionId;

    /**
     * 链路ID
     */
    private String traceId;

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

    /**
     * 扩展信息
     */
    private String metadata;
}
