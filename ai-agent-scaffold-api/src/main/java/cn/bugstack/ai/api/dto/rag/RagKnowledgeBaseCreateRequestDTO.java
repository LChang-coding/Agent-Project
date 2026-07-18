package cn.bugstack.ai.api.dto.rag;

import lombok.Data;

/** 创建知识库请求。 */
@Data
public class RagKnowledgeBaseCreateRequestDTO {
    private String name;
    private String description;
}
