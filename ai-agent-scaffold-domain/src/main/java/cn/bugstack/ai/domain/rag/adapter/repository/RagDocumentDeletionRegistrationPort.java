package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;

/** 文档删除墓碑与任务的本地数据库事务端口。 */
public interface RagDocumentDeletionRegistrationPort {

    /**
     * 在同一本地数据库事务中登记删除状态、任务和 Outbox 事件。
     *
     * @param tenantId 文档所属租户
     * @param registration 已通过领域一致性校验的删除登记内容
     * @return 登记成功时返回 {@code true}；并发请求已登记时返回 {@code false}
     */
    boolean register(String tenantId, RagDocumentDeletionRegistration registration);
}
