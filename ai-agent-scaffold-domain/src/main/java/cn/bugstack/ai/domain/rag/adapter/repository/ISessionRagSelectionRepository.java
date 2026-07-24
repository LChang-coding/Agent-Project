package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;

import java.util.List;

/**
 * 会话RAG手动选择仓储。
 */
public interface ISessionRagSelectionRepository {

    /**
     * 查询会话已选择的绑定。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @return 按选择顺序排列的绑定ID
     */
    List<String> listSelectedBindingIds(String tenantId, String userId, String sessionId);

    /**
     * 原子替换会话选择。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param targetType 绑定目标类型
     * @param targetId 绑定目标ID
     * @param bindingIds 新的绑定ID
     */
    void replaceSelections(String tenantId, String userId, String sessionId,
                           RagBindingTargetType targetType, String targetId, List<String> bindingIds);
}
