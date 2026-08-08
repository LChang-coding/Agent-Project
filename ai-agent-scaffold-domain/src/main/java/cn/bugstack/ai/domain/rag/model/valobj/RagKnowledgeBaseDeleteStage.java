package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库级联删除的处理阶段。
 * <p>任务实体使用枚举声明顺序禁止阶段倒退，因此常量顺序是领域契约。</p>
 */
public enum RagKnowledgeBaseDeleteStage {

    /**
     * 已受理：删除屏障刚立起来，还没开始删任何文档。新建任务的初始阶段。
     */
    RECEIVED,

    /**
     * 逐文档删除中：为库里的每个文档依次发起文档级删除，completedDocuments 记录已经删完几个。
   *
     * <p>子文档删除是异步的，所以协调器常常在这一步主动让出租约转成 WAITING 等下一轮轮询，
     * 而不是死等着占用一个 Worker。</p>
     */
    DELETING_DOCUMENTS,

    /**
   * 零残留验证中：确认库里再没有任何文档、分块和向量残留。
     *
     * <p>complete 强制要求必须停在这一步、且完成数等于总数，否则抛
     * RAG_KB_DELETE_NOT_VERIFIED——不验证就关库会留下永远没人清理的孤儿数据。</p>
   */
    VERIFYING,

    /**
     * 已完成：终点阶段。
     *
     * <p>不能通过 advance 推到这里（advance 明确拒绝目标为 COMPLETED），
     * 只能由 complete 在同一个动作里连同任务终态一起写入。</p>
     */
    COMPLETED
}
