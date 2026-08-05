package cn.bugstack.ai.domain.run.model;

/**
 * 一次「运行」（run）的状态机取值。一次运行就是用户按下发送后，模型或工作流真正干活的那一段过程。
 *
 * <p>所属层次：领域层的运行模型，是整个取消 / 引导 / 恢复机制的事实基础。</p>
 *
 * <p>谁会用它：{@code RunControlService} 用它做状态迁移判断，工具闸门用它决定能不能继续调外部接口，
 * 工作流运行时用它决定要不要继续跑下一个节点，前端拿到它的小写名字用来显示「进行中 / 已取消」。</p>
 *
 * <p>合法迁移路线（只允许沿着这些方向走，不能倒回去）：
 * CREATED（引导预建，还没开跑）→ RUNNING；
 * RUNNING ⇄ WAITING_COMPACTION / WAITING_TOOL（中途等压缩或等工具，仍算在跑）；
 * 任一执行中状态 → STEER_REQUESTED → SUPERSEDED（被用户的新指令顶替）；
 * 任一执行中状态 → CANCEL_REQUESTED → CANCELLING → CANCELLED（用户点了停止）；
 * 任一执行中状态 → COMPLETED（正常跑完）或 FAILED（不可恢复地失败）。</p>
 *
 * <p>为什么必须走到终态：数据库里的状态是「这次运行到底结束了没有」的唯一依据。
 * 如果流跑完了却没把状态推到 COMPLETED/FAILED/CANCELLED，这条运行会永远停在「运行中」，
 * 用户界面一直转圈、会话里的可执行运行列表也永远清不掉，后续新一轮对话就会被判定为「还有运行在跑」。</p>
 *
 * <p>它不负责什么：不存租户、会话、消息等任何数据，也不做迁移合法性校验（那是 RunControlService 配合
 * 数据库乐观锁做的）。这里只回答两个问题：这个状态算不算结束了、还能不能继续干活。</p>
 */
public enum RunStatus {
    /** 引导（steer）时先把后继运行的壳建出来，但还没真正开始执行；只有这个状态允许被客户端重试恢复。 */
    CREATED,
    /** 模型或工作流正在执行，是最常见的活动状态；此状态下取消才有意义。 */
    RUNNING,
    /** 上下文太长，正在做历史压缩；仍算执行中，压缩完会回到正常推理并抬高上下文版本。 */
    WAITING_COMPACTION,
    /** 模型已经说要调某个工具，工具还没执行完；仍算执行中，工具闸门会在这里做取消与版本复核。 */
    WAITING_TOOL,
    /** 已收到用户的新指令（引导），正在把旧运行产生的消息和派生数据作废；是通往 SUPERSEDED 的过渡态。 */
    STEER_REQUESTED,
    /** 已收到取消请求，正在把这次运行牵连的消息、压缩结果、用量记录一并收敛；是通往 CANCELLED 的过渡态。 */
    CANCEL_REQUESTED,
    /** 取消清理执行中；与 CANCEL_REQUESTED 同属「已经不能再产生副作用」的区间。 */
    CANCELLING,
    /** 终态：已被引导创建的后继运行顶替，本次运行的输出不再计入会话历史。 */
    SUPERSEDED,
    /** 终态：取消清理完成，用户看到的结果是「已停止」。 */
    CANCELLED,
    /** 终态：正常跑完并已保存助手回答。 */
    COMPLETED,
    /** 终态：不可恢复失败（模型报错、节点崩溃等），失败原因单独记在运行记录的终态原因字段里。 */
    FAILED;

    /**
     * 判断这次运行是否已经彻底结束，不会再产生任何新输出。
     *
     * <p>四个终态一旦达成就不可再迁移。取消接口、完成接口都靠它做幂等：已经是终态就直接返回，
     * 不再重复作废消息、不再重复抬高上下文版本，避免用户连点两次停止把会话历史清两遍。</p>
     *
     * @return true 表示已是终态，调用方应停止一切后续动作
     */
    public boolean terminal() {
        // 四个终态任意命中即为已结束；注意 CANCEL_REQUESTED、CANCELLING 还不算结束，它们仍在清理过程中。
        return this == SUPERSEDED || this == CANCELLED || this == COMPLETED || this == FAILED;
    }

    /**
     * 判断这次运行现在还允不允许继续干活（继续推理、继续调工具、继续跑下一个节点）。
     *
     * <p>只要用户已经点过取消或发过引导，状态就会离开这四个值，于是所有执行点都会被拦下来。
     * 工具闸门在真正发出 HTTP/MCP 请求前必须靠它复核一次，否则用户明明停止了，外部副作用还会照样发生。</p>
     *
     * @return true 表示可以继续执行
     */
    public boolean executable() {
        // 只有「还没开始」和三种「正在进行中」的状态允许继续；任何取消 / 引导 / 终态都返回 false。
        return this == CREATED || this == RUNNING || this == WAITING_COMPACTION || this == WAITING_TOOL;
    }
}
