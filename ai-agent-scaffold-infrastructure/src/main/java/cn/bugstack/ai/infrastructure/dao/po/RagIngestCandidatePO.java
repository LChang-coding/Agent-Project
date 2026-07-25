package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 全局任务扫描的最小投影，禁止承载任务内容。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestCandidatePO {
    /** 候选任务所属租户；领取时必须回带。 */
    private String tenantId;
    /** 候选摄取任务 ID；不是完整任务快照。 */
    private String jobId;
}
