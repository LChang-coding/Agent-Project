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

    /**
     * 计划执行时间
     */
    private LocalDateTime plannedTime;

    /**
     * 任务状态：pending/running/success/failed/canceled
     */
    private String status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 扩展信息
     */
    private String metadata;
}
