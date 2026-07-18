package cn.bugstack.ai.api.dto.rag;

import lombok.Data;

/** 管理员按已配置运行目标调试 RAG 的请求，不允许直接传 KB scope 或 tenant。 */
@Data
public class RagRetrievalDebugRequestDTO {
    private String targetType;
    private String targetId;
    private String query;
    private Integer maxContextTokens;
}
