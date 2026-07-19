package cn.bugstack.ai.api.dto.rag;

import lombok.Data;

/** 编辑知识库可变信息请求。 */
@Data
public class RagKnowledgeBaseUpdateRequestDTO {
    private String name;
    private String description;
    private Long expectedRevision;
}
