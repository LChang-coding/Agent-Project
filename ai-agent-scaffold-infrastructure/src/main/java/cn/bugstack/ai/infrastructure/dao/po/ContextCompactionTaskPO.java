package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 上下文压缩任务持久化对象。 */
@Data @EqualsAndHashCode(callSuper = true)
public class ContextCompactionTaskPO extends BasePO {
    /** 任务身份、幂等键及可信租户/用户/会话/运行范围。 */
    private String taskId; private String taskKey; private String tenantId; private String userId; private String sessionId; private String runId;
    /** 压缩消息区间、期望记忆版本、上下文基线、覆盖摘要和策略版本。 */
    private Integer fromSequence; private Integer toSequence; private Integer expectedMemoryVersion; private Long baseContextRevision; private String coverageHash; private String policyVersion;
    /** 状态、尝试次数、租约、围栏、失败原因和可观测链路。 */
    private String status; private Integer attemptCount; private String leaseOwner; private java.time.LocalDateTime leaseUntil; private Long fencingToken; private String errorMessage; private String traceId;
}
