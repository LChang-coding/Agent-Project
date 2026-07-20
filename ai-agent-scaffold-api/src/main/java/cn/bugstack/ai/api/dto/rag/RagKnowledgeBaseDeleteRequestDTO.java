package cn.bugstack.ai.api.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagKnowledgeBaseDeleteRequestDTO {
    private Long expectedRevision;
}
