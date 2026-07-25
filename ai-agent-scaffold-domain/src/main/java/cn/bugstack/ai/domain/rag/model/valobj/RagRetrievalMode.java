package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * RAG 召回通道模式。
 * <p>DENSE 语义向量，SPARSE 词项稀疏向量，HYBRID 同时召回并融合。</p>
 */
public enum RagRetrievalMode {
    DENSE,
    SPARSE,
    HYBRID
}
