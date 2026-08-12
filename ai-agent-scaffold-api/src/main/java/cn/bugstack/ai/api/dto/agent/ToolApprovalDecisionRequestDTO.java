package cn.bugstack.ai.api.dto.agent;

import lombok.Data;
import java.util.Map;

@Data
public class ToolApprovalDecisionRequestDTO {
    private String decision;
    private String comment;
    private Map<String,Object> amendedInput;
    private Long expectedRevision;
}
