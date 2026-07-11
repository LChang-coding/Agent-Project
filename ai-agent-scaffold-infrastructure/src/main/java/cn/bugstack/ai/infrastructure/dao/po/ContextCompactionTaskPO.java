package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 上下文压缩任务持久化对象。 */
@Data @EqualsAndHashCode(callSuper = true)
public class ContextCompactionTaskPO extends BasePO {
    private String taskId; private String taskKey; private String tenantId; private String userId; private String sessionId;
    private Integer fromSequence; private Integer toSequence; private Integer expectedMemoryVersion; private String policyVersion;
    private String status; private Integer attemptCount; private String errorMessage; private String traceId;
}
