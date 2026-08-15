package cn.bugstack.ai.domain.run.adapter.repository;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运行记录的持久化出口，领域层通过它读写「一次执行的状态与版本」。
 *
 * <p>解决什么问题：取消、引导、恢复都必须以数据库里的状态为准，而不是内存标记，
 * 因为一次运行可能横跨多个线程甚至多台机器。这个接口把所有涉及运行状态的读写收口到一处，
 * 并且全部方法都强制带上租户与用户，从入口层面杜绝跨租户读写。</p>
 *
 * <p>所属层次：领域层的仓储适配接口（端口），实现在基础设施层用 MyBatis 落到数据库。</p>
 *
 * <p>谁会调用它：只有 {@code RunControlService}。别的服务想改运行状态必须走那个服务，
 * 这样状态机的合法迁移判断才不会被绕过。</p>
 *
 * <p>它不负责什么：不做状态机合法性判断、不做事务边界控制、不发事件、不清理内存注册表。
 * 它只按调用方给的条件执行读写，并如实返回影响行数让调用方自己判断成败。</p>
 */
public interface IChatRunRepository {

    /**
     * 插入一条新的运行记录，是一次执行的起点。
     *
     * <p>写库操作。运行编号在业务上唯一，所以同一个幂等键重复插入会撞唯一约束而失败，
     * 调用方需要先查再插来实现「客户端重试不产生第二次执行」。</p>
     *
     * @param run 已经填好身份、状态、版本和 RAG 快照的运行实体
     * @return 影响行数，正常为 1
     */
    int insert(ChatRunEntity run);

    /**
     * 按可信身份读一条运行记录，不加锁。
     *
     * <p>用于只读判断（查状态、判断是否已取消）。不加锁意味着读到的可能是瞬间过期的值，
     * 因此凡是要产生外部副作用的场合都不能只靠它，必须改用加锁读。</p>
     *
     * @return 运行实体；找不到或不属于该租户与用户时返回 null，调用方需当作「无权访问」处理
     */
    ChatRunEntity query(String tenantId, String userId, String runId);

    /**
     * 按可信身份加行锁读一条运行记录。
     *
     * <p>加锁后在同一事务内其他线程改不动这条运行，用于取消、终结、绑定消息、授权工具调用等
     * 「读到的状态必须在后续写入前保持不变」的场合。必须在事务里调用，否则锁立刻释放等于没加。</p>
     *
     * @return 运行实体；找不到返回 null
     */
    ChatRunEntity lock(String tenantId, String userId, String runId);

    /**
     * 查出某个会话下所有还可能继续产生副作用的运行。
     *
     * <p>用于删除会话、切换配置这类需要先把在跑的东西全部收敛掉的场景。
     * 如果这里漏查，就会出现「会话已删但模型还在后台跑并往里写消息」的脏数据。</p>
     *
     * @return 处于可执行状态的运行列表，没有则为空列表
     */
    List<ChatRunEntity> queryExecutableBySession(String tenantId, String userId, String sessionId);

    /** 查询会话最近一次运行，用于刷新后恢复隐藏子 Agent 的执行时间线。 */
    ChatRunEntity queryLatestBySession(String tenantId, String userId, String sessionId);

    /**
     * 查出某个执行来源（某个 Agent 或某个工作流）下还在跑的运行。
     *
  * <p>用于「工作流要改版或下线，先看看还有没有人正在用它跑」这类前置检查；
     * 注意它不按用户过滤，是租户级视角，因为一个工作流会被多个用户同时使用。</p>
     *
     * @return 该来源上仍处于可执行状态的运行列表
     */
    List<ChatRunEntity> queryExecutableBySource(String tenantId, String sourceType, String sourceId);

    /**
     * 带乐观锁地把运行从一个状态迁到另一个状态，是整个状态机的唯一写入口。
*
     * <p>更新条件同时包含「当前状态必须等于预期状态」和「版本号必须等于预期版本」，
   * 所以并发取消、并发完成只有一个能成功，其余得到影响行数 0。调用方必须检查返回值：
     * 返回 0 表示已经被别人改过，不能当成成功继续往下走，否则会出现两个人各自认为自己收敛了这次运行。</p>
  *
     * @param expectedStatus  期望的当前状态，不匹配则不更新
     * @param targetStatus    要迁入的新状态
     * @param expectedVersion 期望的乐观锁版本，不匹配则不更新
     * @param reason   终态原因文案，用于事后解释这次运行为什么结束
     * @param cancelRequestedAt 用户发起取消或引导的时间，仅取消与引导路径传值
     * @param finishedAt  进入终态的时间，仅终态传值
     * @return 影响行数，1 表示迁移成功，0 表示状态或版本已变化
     */
    int transition(String tenantId, String userId, String runId, RunStatus expectedStatus, RunStatus targetStatus,
                   int expectedVersion, String reason, LocalDateTime cancelRequestedAt, LocalDateTime finishedAt);

    /**
     * 把一条用户消息挂到运行上，并推进乐观锁版本。
     *
* <p>与写消息在同一事务内完成，保证不会出现「消息写进去了但运行不认它」的情况。
     * 返回 0 说明运行在这期间已被取消或引导，调用方必须回滚整个写入。</p>
     *
     * @return 影响行数，1 表示绑定成功
     */
    int bindUserMessage(String tenantId, String userId, String runId, String messageId, int expectedVersion);

    /**
     * 在旧运行上登记它的引导后继，并存下用户给出的新指令。
     *
     * <p>后继关系一旦建立就不允许再分叉：同一条旧运行只能有一个后继，
     * 这样用户连续发两次不同的引导时，第二次会被识别为冲突而不是悄悄创建两条并行的执行链。</p>
     *
     * @return 影响行数，1 表示登记成功
     */
    int bindSuccessor(String tenantId, String userId, String runId, String successorRunId,
                      String steerInstruction, int expectedVersion);

    /**
     * 抬高运行要求的上下文版本号。
     *
     * <p>历史被压缩或被取消作废之后必须调它。版本一变，所有携带旧版本的工具调用和继续推理请求
     * 都会被闸门判定为过期并拒绝，从而防止模型基于已经不存在的历史继续产生外部副作用。</p>
     *
     * @return 影响行数，1 表示更新成功；0 表示期间运行状态已变，调用方应视为并发冲突
     */
    int updateContextRevision(String tenantId, String userId, String runId, long contextRevision, int expectedVersion);
}
