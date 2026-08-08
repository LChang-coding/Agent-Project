package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * RAG 资源在同一租户内的可见范围。
 * <p>租户隔离仍由带 tenantId 的查询保证；本枚举只决定资源是否还需匹配 ownerUserId。</p>
 */
public enum RagVisibility {

    /** 私有；只允许 ownerUserId 与当前用户匹配的请求检索或读取。 */
    PRIVATE,

    /** 租户共享；通过租户范围校验后不再额外匹配 ownerUserId。 */
    TENANT
}
