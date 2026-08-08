package cn.bugstack.ai.domain.rag.model.entity;

import java.util.Map;

/**
 * 不含密钥的检索审计命令。
 * <p>{@code normalizedQuery} 仅供适配器计算摘要，是否保存正文由服务端配置决定。</p>
 *
 * @param retrievalId 本次检索的唯一标识
 * @param tenantId 检索所属租户
 * @param userId 发起检索的可信用户标识
 * @param sessionId 关联会话标识，非会话调用可为空
 * @param runId 关联运行标识，非运行调用可为空
 * @param targetId 实际被授权的 Agent 或工作流标识
 * @param profileId 生效的检索配置标识
 * @param profileRevision 生效的检索配置版本号
 * @param normalizedQuery 规范化后的检索问题
 * @param denseEnabled 本次是否启用稠密向量召回
 * @param sparseEnabled 本次是否启用稀疏词项召回
 * @param rerankEnabled 本次是否启用候选重排
 * @param result 成功或空结果时的检索结果
 * @param status 审计终态：success、empty、failed 或 cancelled
 * @param errorCode 失败时的稳定业务错误码
 * @param errorType 失败时的异常分类
 * @param traceId 关联请求链路的标识
 * @param requestSnapshot 不含查询正文和凭据的有界请求快照
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

    /** 校验审计关联身份、配置版本和终态取值。 */
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
