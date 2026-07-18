package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;

/** 管理员上传知识库文档命令。 */
public record RagDocumentUploadCommand(String tenantId, String userId, String roleCode,
                                       String knowledgeBaseId, RagUploadFileCandidate file) {
}
