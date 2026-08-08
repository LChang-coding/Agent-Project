package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 不可变文档版本的处理状态。
 * <p>所有状态通过 RagDocumentVersionEntity 的迁移方法推进。版本是否当前可检索
 * 仍由逻辑文档的 activeVersionId 和 activeGeneration 决定。</p>
 */
public enum RagDocumentVersionStatus {

    /**
     * 刚创建：版本行已经落库、源文件位置已确定，但还没排进摄取队列。
     *
     * <p>处于该状态时允许调用 processing 开始摄取，也允许直接进入删除。</p>
     */
    CREATED,

    /**
   * 已排队：等着 Worker 来领取。
     *
     * <p>进入方式：上传登记时直接置为排队，或失败版本调用 retryQueued 重新排队。</p>
     *
  * <p>处于该状态时解析器、分块器、Embedding 三个版本号都被清空，
     * 因为重跑可能换成新版组件，旧的版本号留着会让审计对不上。</p>
     */
    QUEUED,

    /**
     * 处理中：Worker 已领取，正在解析、切块、向量化。
  *
     * <p>进入方式：调用 processing 并同时钉死本次使用的解析器/分块器/Embedding 版本，
     * 这三个版本号一旦写入就代表「这批向量是用它们算出来的」，用于日后排查召回质量问题。</p>
     *
     * <p>处于该状态时禁止开始删除（requestDeletion 抛 RAG_DOCUMENT_VERSION_BUSY），
 * 否则会和正在写向量的 Worker 抢同一批外部数据，留下删不干净的残骸。</p>
   */
    PROCESSING,

    /**
     * 就绪：索引已经逐点核验过，这一版可以被激活成活动版本。
     *
     * <p>进入方式：只能从 PROCESSING 调 ready 过来，跳级会抛 IllegalStateException。</p>
     */
    READY,

    /**
     * 处理失败：这一版没跑成，但源文件还在，可以原样重跑。
     *
     * <p>处于该状态时唯一允许的恢复动作是 retryQueued，前提是失败留下的外部副作用
     * （半份向量、半份分块）已经被清理干净。</p>
     */
    FAILED,

    /**
     * 已取消：用户在摄取过程中主动取消。
     *
     * <p>进入方式：取消屏障生效后调用 cancelled；已是终态时重复调用保持幂等，不报错。</p>
     */
    CANCELLED,

    /**
     * 已被顶替：有更新的版本被激活，这一版退出对外可见范围但历史记录仍保留。
     *
     * <p>它算终态，不会再往前走，只可能被删除流程清理掉。</p>
     */
    SUPERSEDED,

    /**
     * 删除中：删除墓碑已立，外部对象和索引正在清理。
     *
     * <p>进入方式：requestDeletion。重复调用幂等。</p>
     */
    DELETING,

    /**
     * 已删除：向量、分块、源文件全部清完并验证。
     *
     * <p>只能从 DELETING 过来，否则抛 RAG_DOCUMENT_VERSION_DELETE_STATE_INVALID。</p>
     */
    DELETED;

    /**
     * 把状态翻译成一个业务判断：这一版还会不会继续被后台处理。
     *
     * <p>用在两处：cancelled() 和 failed() 用它做幂等短路——已经是终态就原样返回，
     * 避免把一个已经 READY 的版本硬改成 FAILED；删除流程用它确认「这一版已经停止写入」，
     * 只有停止写入的版本才允许开始清理外部数据。</p>
     *
     * <p>注意 DELETING 不算终态，因为它还要继续走到 DELETED。</p>
     */
    public boolean terminal() {
        // 这五种状态都意味着不会再有 Worker 往这一版写数据，可以安全地做幂等短路或开始清理。
        return this == READY || this == FAILED || this == CANCELLED || this == SUPERSEDED || this == DELETED;
    }
}
