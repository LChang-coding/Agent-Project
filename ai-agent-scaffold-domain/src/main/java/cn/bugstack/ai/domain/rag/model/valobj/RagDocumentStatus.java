package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 一份逻辑文档在知识库里的生命周期状态。
 *
 * <p>属于哪一层：领域层值对象，是 RagDocumentEntity 的状态字段类型。</p>
 *
 * <p>状态由谁推进：用户上传后由 RagDocumentEntity 的迁移方法推进，实际触发者是摄取 Worker
 * （成功走 activate、失败走 failProcessing、重试走 retryProcessing）和管理员删除请求
 * （requestDeletion → deleted）。检索链路只读不写。</p>
 *
 * <p>它不负责什么：不表示某个具体版本的处理进度（那是 RagDocumentVersionStatus），
 * 也不表示后台任务成功失败（那是 RagIngestJobStatus）。</p>
 */
public enum RagDocumentStatus {

    /**
     * 上传中：文件正在往对象存储写，还没有任何可用版本。
     *
     * <p>处于该状态时不可检索，也不允许开始删除——requestDeletion 会抛 RAG_DOCUMENT_BUSY，
     * 因为此时对象存储里可能还有正在写入的残留文件，先删会留下孤儿对象。</p>
     */
    UPLOADING,

    /**
     * 处理中：已经有一个目标 generation 在跑摄取（解析、切块、向量化、建索引）。
     *
     * <p>进入方式：上传登记成功，或失败后 retryProcessing 恢复。</p>
     *
     * <p>处于该状态时同样不可检索、不可删除；旧的活动版本（如果有）仍然对外可见，
     * 所以用户看到的还是上一版内容，不会中途变空。</p>
     */
    PROCESSING,

    /**
     * 就绪：有一个通过索引校验的活动版本，是唯一允许参与检索的状态。
   *
* <p>进入方式：摄取任务完成后调用 activate 原子切换活动版本和 generation。</p>
     *
     * <p>处于该状态时允许被检索命中，也允许发起删除。</p>
     */
    READY,

    /**
     * 处理失败：这次摄取没成功，但旧的活动版本被完整保留。
     *
     * <p>进入方式：Worker 调用 failProcessing。</p>
     *
     * <p>处于该状态时：如果之前有活动版本，检索仍然能命中旧版本；允许重试（retryProcessing）
  * 也允许直接删除。</p>
     */
    FAILED,

    /**
     * 删除中：删除墓碑已经立起来，文档立刻退出可检索范围，但外部副作用还没清完。
     *
     * <p>进入方式：管理员调用 requestDeletion。这一步是不可撤销的屏障。</p>
     *
   * <p>处于该状态时检索会把命中它的候选当墓碑丢弃；重复请求删除保持幂等，不会重复建任务。</p>
     */
    DELETING,

    /**
     * 已删除：所有版本的向量、业务分块和源文件都清理并验证完毕，活动版本被清空。
     *
     * <p>进入方式：删除任务全部阶段跑完后调用 deleted。</p>
     *
     * <p>这是终态，任何操作都不会再改变它。</p>
     */
    DELETED;

    /**
  * 把状态翻译成一个业务判断：这份文档现在允不允许被检索命中。
     *
     * <p>检索链路（RagRetrievalService）在把向量命中还原成引用之前会调它，返回 false 的文档
   * 会被当作墓碑或未就绪直接剔除，绝不能进入交给模型的上下文，否则用户会看到已删除或半成品的资料。</p>
     *
     * <p>规则很严：只有 READY 算可检索。处理中、失败中、删除中一律不算。</p>
     */
    public boolean searchable() {
  // 只承认「已就绪」这一种状态，其余任何中间态或终态都不允许参与检索。
        return this == READY;
    }
}
