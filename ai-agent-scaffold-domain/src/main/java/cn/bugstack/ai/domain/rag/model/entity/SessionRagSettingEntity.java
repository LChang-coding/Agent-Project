package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode;

import java.util.List;

/**
 * 会话级RAG设置及其绑定可用性。
 * <p>mode/revision 是持久化策略事实；selectedBindingIds 只在 MANUAL 模式生效；
 * eligibleBindings 用于前端选择但不能代替运行创建时的冻结快照。</p>
 *
 * @param sessionId 会话标识
 * @param enabled 兼容旧协议的 RAG 开关值
 * @param mode 会话级知识库选择模式
 * @param invocationMode 检索调用模式
 * @param revision 会话设置版本号
 * @param bindingConfigured 当前运行目标是否存在可用绑定
 * @param targetType 当前会话的绑定目标类型
 * @param targetId 当前会话的 Agent 或工作流标识
 * @param selectedBindingIds 手动模式下用户选择的有序绑定标识
 * @param eligibleBindings 经服务端授权与状态校验后的可选绑定
 */
public record SessionRagSettingEntity(String sessionId,
                                      boolean enabled,
                                      SessionRagMode mode,
                                      RagInvocationMode invocationMode,
                                      long revision,
                                      boolean bindingConfigured,
                                      RagBindingTargetType targetType,
                                      String targetId,
                                      List<String> selectedBindingIds,
                                      List<SessionRagEligibleBindingEntity> eligibleBindings) {

    /** 将可空列表规范为不可变空列表或防御副本。 */
    public SessionRagSettingEntity {
        selectedBindingIds = selectedBindingIds == null ? List.of() : List.copyOf(selectedBindingIds);
        eligibleBindings = eligibleBindings == null ? List.of() : List.copyOf(eligibleBindings);
    }

    /**
     * 使用历史默认的自动上下文调用模式创建会话设置。
     *
     * @param sessionId 会话标识
     * @param enabled 兼容旧协议的 RAG 开关值
     * @param mode 会话级选择模式
     * @param revision 会话设置版本号
     * @param bindingConfigured 当前目标是否存在可用绑定
     * @param targetType 绑定目标类型
     * @param targetId 绑定目标标识
     * @param selectedBindingIds 手动选择的绑定标识
     * @param eligibleBindings 可选绑定摘要
     */
    public SessionRagSettingEntity(String sessionId, boolean enabled, SessionRagMode mode, long revision,
                                   boolean bindingConfigured, RagBindingTargetType targetType, String targetId,
                                   List<String> selectedBindingIds,
                                   List<SessionRagEligibleBindingEntity> eligibleBindings) {
        this(sessionId, enabled, mode, RagInvocationMode.AUTO_CONTEXT, revision, bindingConfigured,
                targetType, targetId, selectedBindingIds, eligibleBindings);
    }
}
