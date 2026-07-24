package cn.bugstack.ai.domain.rag.model.entity;

import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;

import java.util.List;

/**
 * 创建运行时冻结的会话RAG策略。
 */
public record SessionRagRunSnapshotEntity(SessionRagMode mode,
                                          long revision,
                                          List<String> bindingIds) {

    public SessionRagRunSnapshotEntity {
        if (mode == null || revision < 0) {
            throw new IllegalArgumentException("RAG运行快照参数非法");
        }
        bindingIds = bindingIds == null ? List.of() : List.copyOf(bindingIds);
        if (mode == SessionRagMode.OFF && !bindingIds.isEmpty()) {
            throw new IllegalArgumentException("关闭RAG时不能冻结绑定");
        }
        // 兼容历史Run可能只有布尔快照；新生产Run由服务层保证启用时绑定非空。
    }

    public boolean enabled() {
        return mode != SessionRagMode.OFF;
    }
}
