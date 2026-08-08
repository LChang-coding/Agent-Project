package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode;

import java.util.List;

/**
 * 创建运行时冻结的会话 RAG 策略。
 * <p>运行期始终使用该快照，后续会话设置修改不影响已创建的运行。</p>
 *
 * @param mode 运行创建时的会话 RAG 选择模式
 * @param invocationMode 检索是自动注入还是由平台工具发起
 * @param revision 快照对应的会话设置版本号
 * @param bindingIds 本轮运行允许使用的有序绑定标识
 */
public record SessionRagRunSnapshotEntity(SessionRagMode mode,
                                           RagInvocationMode invocationMode,
                                           long revision,
                                           List<String> bindingIds) {

    /**
     * 使用历史默认的自动上下文模式创建运行快照。
     *
     * @param mode 会话 RAG 选择模式
     * @param revision 会话设置版本号
     * @param bindingIds 本轮运行冻结的绑定标识
     */
    public SessionRagRunSnapshotEntity(SessionRagMode mode, long revision, List<String> bindingIds) {
        this(mode, RagInvocationMode.AUTO_CONTEXT, revision, bindingIds);
    }

    /** 校验运行模式与冻结绑定的一致性，并保存绑定列表防御副本。 */
    public SessionRagRunSnapshotEntity {
        if (mode == null || invocationMode == null || revision < 0) {
            throw new IllegalArgumentException("RAG运行快照参数非法");
        }
        bindingIds = bindingIds == null ? List.of() : List.copyOf(bindingIds);
        if (mode == SessionRagMode.OFF && !bindingIds.isEmpty()) {
            throw new IllegalArgumentException("关闭RAG时不能冻结绑定");
        }
        // 兼容历史Run可能只有布尔快照；新生产Run由服务层保证启用时绑定非空。
    }

    /**
     * 判断本轮运行是否启用 RAG。
     *
     * @return 模式不是 OFF 时返回 {@code true}
     */
    public boolean enabled() {
        return mode != SessionRagMode.OFF;
    }
}
