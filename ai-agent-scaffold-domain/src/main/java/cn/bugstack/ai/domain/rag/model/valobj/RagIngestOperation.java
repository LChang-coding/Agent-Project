package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 一条 RAG 后台任务到底在干什么活。
 *
 * <p>属于哪一层：领域层值对象。它在摄取任务创建时确定，之后终身不变——
 * RagIngestJobEntity 的每个状态迁移方法都会先看它，用错方法就抛领域异常。</p>
 *
 * <p>谁会读它：RagIngestJobEntity 用它把「摄取流水线」和「删除流水线」两套阶段推进彻底隔开；
 * Worker 用它选择要执行哪条流水线。</p>
 *
 * <p>它不负责什么：不表示任务跑到哪一步（那是 checkpoint 的 stage），也不表示成功失败（那是 status）。</p>
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
