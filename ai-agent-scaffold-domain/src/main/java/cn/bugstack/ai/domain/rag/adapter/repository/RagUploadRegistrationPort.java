package cn.bugstack.ai.domain.rag.adapter.repository;

import cn.bugstack.ai.domain.rag.model.entity.RagUploadRegistration;

/** 文档上传的本地数据库事务端口。 */
public interface RagUploadRegistrationPort {

    /**
     * 原子登记上传；任务幂等键已存在时返回 false，除此之外的失败必须回滚并抛出。
     */
    boolean register(String tenantId, RagUploadRegistration registration);
}
