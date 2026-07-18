package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 全局任务扫描的最小投影，禁止承载任务内容。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestCandidatePO {
    private String tenantId;
    private String jobId;
}
