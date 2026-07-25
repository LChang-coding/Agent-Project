package cn.bugstack.ai.domain.rag.adapter.port;

import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalAuditCommand;

/** RAG 检索与引用留痕端口；实现必须保证主记录和引用同事务。 */
public interface RagRetrievalAuditPort {

    /** 原子记录检索主记录及其最终引用集合。 */
    void record(RagRetrievalAuditCommand command);
}
