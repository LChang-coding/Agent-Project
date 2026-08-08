package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库生命周期状态。
 * <p>该状态决定知识库是否可进入在线检索范围；文档可用性和删除任务进度
 * 由各自的状态对象表达。</p>
 */
public enum RagKnowledgeBaseStatus {

/**
     * 启用：唯一允许在线检索的状态。
 *
     * <p>进入方式：创建成功，或摄取任务完成后调用 activateGeneration 推进可见代次时顺带置为启用。</p>
     *
     * <p>但光是 ACTIVE 还不够：检索侧还会要求 currentGeneration 大于 0，
  * 因为一个还没有任何一代索引的库检索出来必然是空的。</p>
  */
    ACTIVE,

    /**
     * 人工停用：管理员临时关掉，数据都在但不参与检索。
     *
     * <p>处于该状态时所有引用它的绑定都会被判为不可用；如果某个绑定是 required，
     * 对话会直接报「必需知识库不可用」而不是悄悄少查一份资料。</p>
     *
     * <p>允许从这个状态发起删除。</p>
     */
    DISABLED,

    /**
     * 正在构建新一代索引。
     *
* <p>处于该状态时不可检索，也不允许发起删除（会抛 RAG_KNOWLEDGE_BASE_DELETE_STATE_INVALID），
     * 因为正在写入的索引数据还没有确定归属，删一半会留下清不掉的向量。</p>
     */
    INDEXING,

    /**
     * 删除中：级联删除屏障已经立起来，不可撤销。
  *
     * <p>进入方式：requestDeletion，只允许从 ACTIVE 或 DISABLED 进入；重复调用幂等。</p>
     *
     * <p>处于该状态时库立刻退出检索范围，绑定停用，后台开始逐个文档清理。</p>
     */
    DELETING,

    /**
     * 已删除：所有子文档的外部副作用都清完并通过零残留验证。
     *
     * <p>只能从 DELETING 过来。再次请求删除会抛 RAG_KNOWLEDGE_BASE_ALREADY_DELETED，
     * 这是故意的——让调用方知道自己在操作一个已经不存在的库，而不是静默成功。</p>
     */
    DELETED;

    /**
     * 判断知识库是否允许进入在线检索范围。
     * @return 状态为 ACTIVE 时返回 {@code true}
     */
    public boolean searchable() {
        return this == ACTIVE;
    }
}
