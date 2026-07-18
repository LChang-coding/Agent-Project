package cn.bugstack.ai.api.dto.rag;

import lombok.Builder;
import lombok.Data;

/** Agent/工作流 RAG 绑定响应。 */
@Data
@Builder
public class RagBindingResponseDTO {
    private String bindingId;
    private String targetType;
    private String targetId;
    private String knowledgeBaseId;
    private String profileId;
    private Boolean required;
    private Integer maxTokens;
    private Integer priority;
    private Long revision;
}
