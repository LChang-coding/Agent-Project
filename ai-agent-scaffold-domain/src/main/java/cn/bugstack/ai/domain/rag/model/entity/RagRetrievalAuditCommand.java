package cn.bugstack.ai.domain.rag.model.entity;

import java.util.Map;

/**
 * 不含密钥的检索审计命令。normalizedQuery 仅供适配器计算摘要，是否保存正文由服务端配置决定。
 */
public record RagRetrievalAuditCommand(String retrievalId,
                                       String tenantId,
                                       String userId,
                                       String sessionId,
                                       String runId,
                                       String targetId,
                                       String profileId,
                                       long profileRevision,
                                       String normalizedQuery,
                                       boolean denseEnabled,
                                       boolean sparseEnabled,
                                       boolean rerankEnabled,
                                       RagRetrievalResult result,
                                       String status,
                                       String errorCode,
                                       String errorType,
                                       String traceId,
                                       Map<String, Object> requestSnapshot) {

    public RagRetrievalAuditCommand {
        requireText(retrievalId, "检索ID");
        requireText(tenantId, "租户ID");
        requireText(userId, "用户ID");
        requireText(targetId, "目标ID");
        requireText(profileId, "策略ID");
        requireText(normalizedQuery, "规范化查询");
        requireText(status, "状态");
        if (profileRevision < 0 || !("success".equals(status) || "empty".equals(status)
                || "failed".equals(status) || "cancelled".equals(status))) {
            throw new IllegalArgumentException("RAG检索审计参数非法");
        }
        requestSnapshot = requestSnapshot == null ? Map.of() : Map.copyOf(requestSnapshot);
    }

    /** 校验审计关联身份和状态文本。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }
}
