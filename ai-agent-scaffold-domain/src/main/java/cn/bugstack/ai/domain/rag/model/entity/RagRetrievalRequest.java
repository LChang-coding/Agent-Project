package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

/**
 * 一次使用可信运行身份发起的 RAG 检索请求。
 */
public record RagRetrievalRequest(String tenantId,
                                  String userId,
                                  String sessionId,
                                  String runId,
                                  RagBindingTargetType targetType,
                                  String targetId,
                                  String query,
                                  String traceId,
                                  int maxContextTokens) {

    public RagRetrievalRequest {
        requireText(tenantId, "租户ID");
        requireText(userId, "用户ID");
        requireText(targetId, "绑定目标ID");
        requireText(query, "检索问题");
        if (targetType == null || maxContextTokens < 1) {
            throw new IllegalArgumentException("RAG检索请求参数非法");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
