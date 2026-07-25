package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * RAG 资源可见范围。
 * <p>PRIVATE 仅所有者可用；TENANT 对同租户授权主体可用，任何情况都不跨租户。</p>
 */
public enum RagVisibility {
    PRIVATE,
    TENANT
}
