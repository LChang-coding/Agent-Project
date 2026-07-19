package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;

/** 文档删除墓碑与任务的本地数据库事务端口。 */
public interface RagDocumentDeletionRegistrationPort {

    /** 原子登记删除；并发请求已由另一个事务受理时返回 false。 */
    boolean register(String tenantId, RagDocumentDeletionRegistration registration);
}
