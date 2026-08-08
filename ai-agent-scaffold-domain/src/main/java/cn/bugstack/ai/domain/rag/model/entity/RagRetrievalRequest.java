package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一次使用可信运行身份发起的 RAG 检索请求。
 *
 * @param tenantId 发起检索的租户标识
 * @param userId 发起检索的服务端可信用户标识
 * @param sessionId 关联会话标识，非会话调用可为空
 * @param runId 关联运行标识，非运行调用可为空
 * @param targetType 授权绑定所属目标类型
 * @param targetId 当前 Agent 或工作流标识
 * @param query 用于检索的真实用户问题
 * @param traceId 关联请求链路和外部模型调用的标识
 * @param maxContextTokens 本次检索结果允许使用的最大 Token 数
 * @param diagnosticsEnabled 是否收集有界候选诊断信息
 * @param bindingIds 运行创建时冻结的可用绑定标识
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

    /**
     * 创建不开启诊断且由服务端解析全部绑定的检索请求。
     * @param tenantId 发起检索的租户标识
     * @param userId 发起检索的可信用户标识
     * @param sessionId 关联会话标识
     * @param runId 关联运行标识
     * @param targetType 绑定目标类型
     * @param targetId 绑定目标标识
     * @param query 检索问题
     * @param traceId 请求链路标识
     * @param maxContextTokens 最大上下文 Token 数
     */
    public RagRetrievalRequest(String tenantId, String userId, String sessionId, String runId,
                               RagBindingTargetType targetType, String targetId, String query,
                               String traceId, int maxContextTokens) {
        this(tenantId, userId, sessionId, runId, targetType, targetId, query, traceId,
                maxContextTokens, false, List.of());
    }

    /**
     * 创建由服务端解析全部绑定的检索请求。
     * @param tenantId 发起检索的租户标识
     * @param userId 发起检索的可信用户标识
     * @param sessionId 关联会话标识
     * @param runId 关联运行标识
     * @param targetType 绑定目标类型
     * @param targetId 绑定目标标识
     * @param query 检索问题
     * @param traceId 请求链路标识
     * @param maxContextTokens 最大上下文 Token 数
     * @param diagnosticsEnabled 是否收集有界候选诊断
     */
    public RagRetrievalRequest(String tenantId, String userId, String sessionId, String runId,
                               RagBindingTargetType targetType, String targetId, String query,
                               String traceId, int maxContextTokens, boolean diagnosticsEnabled) {
        this(tenantId, userId, sessionId, runId, targetType, targetId, query, traceId,
                maxContextTokens, diagnosticsEnabled, List.of());
    }

    /** 校验可信身份、检索目标和 Token 预算，并按顺序去重冻结绑定。 */
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
