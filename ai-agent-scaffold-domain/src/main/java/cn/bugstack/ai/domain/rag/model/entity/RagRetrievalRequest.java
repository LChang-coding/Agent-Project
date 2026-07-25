package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

import java.util.LinkedHashSet;
import java.util.List;

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
                                  int maxContextTokens,
                                  boolean diagnosticsEnabled,
                                  List<String> bindingIds) {

    public RagRetrievalRequest(String tenantId, String userId, String sessionId, String runId,
                               RagBindingTargetType targetType, String targetId, String query,
                               String traceId, int maxContextTokens) {
        this(tenantId, userId, sessionId, runId, targetType, targetId, query, traceId,
                maxContextTokens, false, List.of());
    }

    public RagRetrievalRequest(String tenantId, String userId, String sessionId, String runId,
                               RagBindingTargetType targetType, String targetId, String query,
                               String traceId, int maxContextTokens, boolean diagnosticsEnabled) {
        this(tenantId, userId, sessionId, runId, targetType, targetId, query, traceId,
                maxContextTokens, diagnosticsEnabled, List.of());
    }

    public RagRetrievalRequest {
        requireText(tenantId, "租户ID");
        requireText(userId, "用户ID");
        requireText(targetId, "绑定目标ID");
        requireText(query, "检索问题");
        if (targetType == null || maxContextTokens < 1) {
            throw new IllegalArgumentException("RAG检索请求参数非法");
        }
        bindingIds = bindingIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(bindingIds));
        if (bindingIds.size() > 32 || bindingIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("RAG绑定快照非法");
        }
    }

    /** 校验检索身份、目标和真实用户问题。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
