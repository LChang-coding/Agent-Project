package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 知识库级联删除任务的执行状态。
 *
 * <p>属于哪一层：领域层值对象，是 RagKnowledgeBaseDeleteTaskEntity 的状态字段类型。</p>
 *
 * <p>状态由谁推进：只能通过删除任务实体的迁移方法推进（claim / advance / waitForChild /
 * complete / fail / requeue），调用方是知识库删除协调器。</p>
 *
 * <p>和摄取任务状态的最大区别：这里多了一个 WAITING。级联删除要等一堆子文档删完，
 * 与其让 Worker 干等着，不如主动释放租约、约定下次轮询时间，而且这种等待<b>不算失败</b>、
 * 不消耗重试次数——否则一个文档多的库还没删完就把尝试次数耗光了。</p>
 */
public enum RagKnowledgeBaseDeleteStatus {

    /**
     * 待领取：删除屏障已立、任务账本已落库，还没有协调器拿走。
     *
   * <p>进入方式：pending 工厂方法新建，或 requeue 人工重新排队（尝试次数归零）。</p>
   */
    PENDING,

    /**
     * 运行中：某个协调器持有有效租约正在删。
     *
     * <p>构造校验强制「RUNNING 必须有租约、非 RUNNING 必须没有租约」，
     * 保证任何时刻都能一眼看出这个任务有没有人负责。</p>
  */
    RUNNING,

    /**
     * 等待子文档：本轮该发起的文档删除都发出去了，正在等它们各自跑完。
     *
     * <p>进入方式：waitForChild，同时写入 nextRetryAt 作为下次轮询时间并释放租约。</p>
*
     * <p>关键点：从 WAITING 被重新领取时 attemptCount <b>不会</b>加一（见 claim 里的分支），
     * 因为等待不是失败；如果算成失败，删一个上千文档的库必然中途被判定为耗尽。</p>
   */
    WAITING,

    /**
     * 等待重试：删除过程中出现可恢复错误，到 nextRetryAt 后可再领取。
     *
     * <p>进入方式：fail 且 retryable 为真、尝试次数还没用完。这种领取会消耗一次尝试次数。</p>
     */
    RETRYING,

    /**
   * 已完成：零残留验证通过，知识库墓碑和任务在同一个事务里一起关闭。是终态。
     */
    COMPLETED,

    /**
     * 失败：遇到不可恢复错误（retryable 为假）直接关闭。是终态，但允许人工 requeue。
   */
    FAILED,

    /**
     * 彻底放弃：可恢复错误但重试次数耗尽，需要人工介入。是终态，允许人工 requeue。
     */
    DEAD;

    /**
     * 把状态翻译成一个业务判断：协调器现在能不能领这个任务。
     *
     * <p>由 claim 调用，覆盖四种场景：首次领取（PENDING）、等子文档后回来接着干（WAITING）、
     * 失败后到期重试（RETRYING）、原持有者失联后接管（RUNNING）。</p>
     *
  * <p>RUNNING 能通过这一关只是第一道门，claim 随后还会检查租约是否真的过期，
     * 没过期就抛 RAG_KB_DELETE_LEASE_ACTIVE，避免两个协调器同时删同一个库。</p>
     */
    public boolean claimable() {
        // 这四种状态都可能需要协调器接手：新任务、等待子任务归来、到期重试、以及接管失联的运行中任务。
        return this == PENDING || this == WAITING || this == RETRYING || this == RUNNING;
    }

    /**
     * 把状态翻译成一个业务判断：这个任务是不是已经不会再自动往前走了。
     *
     * <p>上层用它决定要不要继续轮询这条任务，以及 requeue 时判断是否允许重新排队
     * （只有 FAILED 和 DEAD 允许，其余抛 RAG_KB_DELETE_REQUEUE_STATE_INVALID）。</p>
     */
    public boolean terminal() {
        // 完成、失败、放弃这三种都不会再被自动调度，只可能由人工重新排队。
        return this == COMPLETED || this == FAILED || this == DEAD;
    }
}
