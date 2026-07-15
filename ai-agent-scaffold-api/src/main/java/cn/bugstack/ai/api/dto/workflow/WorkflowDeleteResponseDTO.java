package cn.bugstack.ai.api.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工作流软删除响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDeleteResponseDTO {
    private String workflowId;
    private String status;
}
