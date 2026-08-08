package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 摄取或文档删除任务的处理阶段。
 * <p>摄取阶段使用枚举声明顺序判断能否前进，因此常量顺序是领域契约，不能任意调整。
 * 删除阶段使用独立的迁移校验。</p>
 */
public enum RagIngestStage {

    /**
   * 已受理：任务刚建好，还没做任何实际处理。所有新任务和删除任务都从这里开始。
     */
    RECEIVED,

    /**
     * 解析中：把源文件转成结构化中间表示，产出解析产物并落对象存储。
     *
     * <p>这一步产生的解析事实（对象位置、内容哈希、页数、字符数）一旦写进检查点就不许再改，
* 后续检查点必须原样带着，否则会被判为检查点非法。</p>
   */
    PARSING,

    /**
     * 切块中：按结构把文档拆成父子分块，并算出 totalChunks。
     */
    CHUNKING,

    /**
     * 向量化中：分批把分块文本送进 Embedding 模型，embeddingBatchIndex 记录做到第几批。
     */
    EMBEDDING,

    /**
     * 写索引中：把向量点写进向量库，vectorUpsertIndex 记录写到第几条，重跑时从这里续上。
  */
    INDEXING,

 /**
     * 验证中：逐点核对向量库里的点标识和内容哈希是否与数据库分块完全一致。
     *
     * <p>这是完成前的最后一道闸门：complete 要求必须停在这一步，并且 totalChunks 大于 0、
     * processedChunks 和 vectorUpsertIndex 都等于 totalChunks，否则抛
     * RAG_INGEST_INDEX_INCOMPLETE，防止把一份只索引了一半的文档激活成可检索版本。</p>
     */
    VERIFYING,

    /**
     * 已完成：终点阶段。
     *
     * <p>不能通过 advance 主动推到这一步（advance 明确拒绝目标为 COMPLETED），
     * 只能由 complete / completeDeletion 在校验全部通过后一次性写入，
     * 保证「完成」这件事和任务状态、索引激活在同一个动作里发生。</p>
     */
    COMPLETED,

    /**
     * 删除向量中：清掉这一版在向量库里的全部向量点。删除流水线的第一步。
     *
     * <p>顺序刻意排在最前：先让资料检索不到，再删业务分块和源文件，
     * 这样任何中间时刻都不会出现「向量还在但正文已经没了」的悬空引用。</p>
     */
    DELETING_VECTORS,

    /**
     * 删除分块中：物理清掉数据库里这一版的分块正文。
     */
    DELETING_CHUNKS,

    /**
     * 删除源文件中：清掉对象存储里的原始文件和解析产物。删除流水线的最后一步，
     * 只有停在这里才允许 completeDeletion 关闭任务。
     */
    DELETING_SOURCE;

    /**
     * 判断能不能从当前阶段走到目标阶段。
     *
     * <p>规则只允许两种：留在原地（用于幂等重放，Worker 重跑同一步不算倒退）
     * 或前进恰好一格。跳级和倒退都返回 false，调用方会抛
     * RAG_INGEST_CHECKPOINT_REGRESSION。</p>
     *
     * <p>为什么要这么严：如果允许跳级，一个重启后的 Worker 可能从「切块中」直接跳到「验证中」，
     * 而向量其实一条都没写，最后就会激活一份空索引，用户检索什么都查不到却看不出问题。</p>
     *
     * <p>注意它只服务摄取流水线的连续阶段；删除流水线的三步在枚举里不与摄取相邻，
     * 所以走的是 RagIngestJobEntity 里单独的 validDeleteTransition 判断。</p>
     */
    public boolean canAdvanceTo(RagIngestStage target) {
        // 目标必须存在，且要么就是当前阶段（幂等重放），要么是紧邻的下一个阶段（禁止跳级和倒退）。
        return target != null && (target.ordinal() == ordinal() || target.ordinal() == ordinal() + 1);
    }
}
