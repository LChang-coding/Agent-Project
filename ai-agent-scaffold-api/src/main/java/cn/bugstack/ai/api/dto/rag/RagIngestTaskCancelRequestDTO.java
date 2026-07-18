package cn.bugstack.ai.api.dto.rag;

import lombok.Data;

/** 摄取任务取消请求。 */
@Data
public class RagIngestTaskCancelRequestDTO {
    private String reason;
}
