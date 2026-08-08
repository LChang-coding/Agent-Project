package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagUploadRegistration;

/** 文档上传的本地数据库事务端口。 */
public interface RagUploadRegistrationPort {

    /**
     * 原子登记上传；任务幂等键已存在时返回 false，除此之外的失败必须回滚并抛出。
     *
     * @param tenantId 文档所属租户
     * @param registration 待原子写入的文档、版本、任务和 Outbox 事件
     * @return 登记成功时返回 {@code true}；幂等键已存在时返回 {@code false}
     */
    boolean register(String tenantId, RagUploadRegistration registration);
}
