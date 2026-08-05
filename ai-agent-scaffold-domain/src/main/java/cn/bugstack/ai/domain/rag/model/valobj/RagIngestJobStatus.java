package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 一条文档摄取任务（或删除任务）的执行状态。
 *
 * <p>属于哪一层：领域层值对象，是 RagIngestJobEntity 的状态字段类型。</p>
 *
 * <p>状态由谁推进：只能通过 RagIngestJobEntity 上的迁移方法推进，实际触发者是摄取 Worker
 * （claim 领取、advance 推进、complete 完成、failRetryable / failTerminal 记故障）
 * 和管理端取消请求（requestCancel）。任何绕过实体直接改数据库状态的写法都会破坏这里的不变量。</p>
 *
 * <p>它不负责什么：不表示任务跑到流水线哪一步（那是 checkpoint 里的 RagIngestStage），
 * 也不表示任务在干什么活（那是 RagIngestOperation）。</p>
 */
public enum RagIngestJobStatus {

    /**
     * 待领取：任务已经落库并发出唤醒消息，还没有任何 Worker 拿到它。
     *
     * <p>进入方式：新建任务（pending 工厂方法），或失败任务重新排队（requeueIngest / requeueDeletion）。</p>
     *
     * <p>处于该状态时不允许持有租约（构造校验会拦），因为没人在跑；允许被领取。</p>
     */
    PENDING,

    /**
     * 运行中：某个 Worker 持有有效租约正在执行。
     *
     * <p>进入方式：claim 领取成功。</p>
     *
* <p>处于该状态时必须持有租约（否则构造直接抛异常），这是「运行中就一定有人负责」的硬约束。
     * 也只有这个状态允许调用外部服务：assertExternalCallAllowed 会先检查状态是不是 RUNNING，
  * 不是就拒绝，避免一个已取消的任务还在往向量库里写数据。</p>
     */
    RUNNING,

    /**
     * 等待重试：这次跑失败了但还有剩余尝试次数，到 nextRetryAt 之后可以再被领取。
     *
     * <p>进入方式：failRetryable 且 attemptCount 还没用完。</p>
     *
     * <p>处于该状态时必须有 nextRetryAt（构造校验强制一致），租约已经被释放；
     * 时间没到就领不走，避免失败任务被瞬间反复重试打爆下游模型服务。</p>
     */
    RETRYING,

    /**
 * 已请求取消：取消屏障已经立起来，但外部副作用还没清干净。
     *
 * <p>进入方式有两种：用户主动取消（requestCancel，cancelReason 记人工原因），
     * 或运行中任务遇到终止性故障需要先清理副作用（requestFailureCleanup，
     * cancelReason 被写成 SYSTEM_FAILURE_CLEANUP:FAILED / :DEAD 这种内部标记）。</p>
     *
     * <p>处于该状态时必须有取消原因；外部调用被 assertExternalCallAllowed 拦死，
     * 但租约可以保留，宕机后还能由别的 Worker 接管来继续做清理。</p>
     */
    CANCEL_REQUESTED,

    /**
     * 已取消：清理完成，任务关闭。是终态。
     *
     * <p>进入方式：markCancelled。重复调用幂等，不会报错。</p>
     */
    CANCELLED,

    /**
     * 已完成：索引验证通过、所有分块和向量数量对齐，任务成功关闭。是终态。
     *
     * <p>进入方式：complete（摄取）或 completeDeletion（删除）。</p>
 *
     * <p>构造校验强制它与 checkpoint 的 COMPLETED 阶段成对出现：状态说完成但阶段没到，
     * 或阶段到了状态没变，都会被当成脏数据直接拒绝。</p>
     */
    COMPLETED,

    /**
     * 失败：遇到不可重试的问题，或失败副作用清理完毕后按预定目标关闭。是终态。
     *
     * <p>进入方式：failTerminal，或 markFailedAfterCleanup 且原定目标是 FAILED。</p>
     *
     * <p>摄取任务在这个状态下可以由人工触发 requeueIngest 从头重跑；
   * 删除任务可以 requeueDeletion 接着上次的检查点继续删。</p>
     */
    FAILED,

    /**
     * 彻底放弃：重试次数已经耗尽，不再自动恢复。是终态。
 *
     * <p>进入方式：failRetryable 时发现 attemptCount 已达上限，或清理后目标是 DEAD。</p>
     *
     * <p>和 FAILED 的区别只在语义上——DEAD 表示「自动化已经尽力了，请人工介入」，
     * 同样支持人工重新排队。</p>
     */
    DEAD;

    /**
     * 把状态翻译成一个业务判断：这个任务是不是已经走到头、不会再往前动了。
     *
   * <p>用在两处：requestCancel 用它拦住「取消一个已经结束的任务」这种无意义操作，
     * 直接抛 RAG_INGEST_ALREADY_TERMINAL；上层轮询和清理逻辑用它判断能不能停止关注这条任务。</p>
     *
     * <p>注意 CANCEL_REQUESTED 不算终态——它还欠一次清理，必须继续被 Worker 接管处理完。</p>
     */
    public boolean terminal() {
    // 这四种状态都表示任务已经关闭，既不会再被领取，也不会再产生任何外部副作用。
        return this == CANCELLED || this == COMPLETED || this == FAILED || this == DEAD;
    }

    /**
     * 把状态翻译成一个业务判断：Worker 现在能不能来抢这个任务。
     *
     * <p>由 claim 调用。除了状态可领，claim 还要再检查两件事：重试时间是否已到，
   * 以及 RUNNING 的任务租约是否真的过期了——只有原持有者失联（租约超时）才允许别人接管，
     * 否则两个 Worker 会同时往同一批向量里写，造成重复索引和数量核验失败。</p>
   *
     * <p>RUNNING 之所以算「可领」，正是为了支持这种故障接管；接管时靠单调递增的
     * fencing token 让旧 Worker 后续的写入全部被拒绝。</p>
*/
    public boolean claimable() {
        // 待领取和到期重试是正常领取；RUNNING 只在原租约已过期的接管场景下才会真正通过后续校验。
        return this == PENDING || this == RETRYING || this == RUNNING;
    }
}
