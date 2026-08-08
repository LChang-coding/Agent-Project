package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;

/**
 * 管理员上传知识库文档的领域命令。
 *
 * @param tenantId 发起上传的租户标识
 * @param userId 发起上传的用户标识
 * @param roleCode 服务端已验证的用户角色
 * @param knowledgeBaseId 接收文档的知识库标识
 * @param file 已写入受控临时目录的待校验文件
 */
public record RagDocumentUploadCommand(String tenantId, String userId, String roleCode,
                                       String knowledgeBaseId, RagUploadFileCandidate file) {
}
