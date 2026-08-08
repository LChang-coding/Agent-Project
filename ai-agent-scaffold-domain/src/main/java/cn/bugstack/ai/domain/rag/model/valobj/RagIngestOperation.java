package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * RAG 后台任务的操作类型。
 * <p>操作类型在任务创建后保持不变，并用于限制可使用的检查点推进和生命周期方法。</p>
 */
public enum RagIngestOperation {

    /**
     * 摄取：把一个新上传的不可变文档版本解析、切块、向量化并建索引。
     *
     * <p>只有这种任务能调用 advance / complete 推进摄取阶段，也只有它能被用户取消、
     * 能在失败清理完成后用 requeueIngest 从头重跑。</p>
     */
    INGEST,

    /**
     * 重建：为整个知识库重新生成一代索引。
     *
     * <p>链路尚未实现，requeueIngest 遇到它会直接抛 RAG_REBUILD_NOT_IMPLEMENTED，
     * 属于「占了位但还不能用」的枚举值。</p>
     */
    REBUILD,

    /**
     * 删除：不可逆地清掉某个文档版本的向量、业务分块和源文件。
     *
     * <p>一旦开始就不允许取消（requestCancel 会抛 RAG_DELETE_NOT_CANCELLABLE），
     * 因为副作用已经在外部系统里发生了，取消只会留下半清理的残骸。
     * 它走 advanceDeletion / completeDeletion 这套独立阶段，失败后可以用 requeueDeletion 接着删。</p>
     */
    DELETE
}
