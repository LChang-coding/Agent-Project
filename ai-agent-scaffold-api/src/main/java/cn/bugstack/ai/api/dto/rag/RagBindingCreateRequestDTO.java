package cn.bugstack.ai.api.dto.rag;

import lombok.Data;

/** Agent/工作流到租户知识库的绑定请求。 */
@Data
public class RagBindingCreateRequestDTO {
    private String targetType;
    private String targetId;
    private String knowledgeBaseId;
    private String profileId;
    private Boolean required;
    private Integer maxTokens;
    private Integer priority;
}
