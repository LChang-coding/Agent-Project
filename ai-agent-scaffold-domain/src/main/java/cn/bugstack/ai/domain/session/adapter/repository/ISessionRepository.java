package cn.bugstack.ai.domain.session.adapter.repository;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话与消息的读写出口，所有方法都强制带「租户 + 用户 + 会话」这组复合范围。
 *
 * <p>所属层次：领域层（domain）session 子域的适配器出口。实现在基础设施层
 * （MyBatis 仓储 + mapper XML），由 Spring 注入。</p>
 *
 * <p>谁会调用它：{@code SessionDomain}（几乎全部方法）和 {@code SessionLifecycleService}（删除流程）。
 * 其他子域一律通过 SessionDomain 间接使用，不直接依赖这个接口。</p>
 *
 * <p>为什么每个方法都要带完整身份：这组条件会原样出现在 SQL 的 WHERE 里，是防越权的最后一道闸门。
 * 只要少传一个，SQL 的范围就会放大，可能读到甚至改到别人的会话。tenantId 允许为 null（个人模式），
 * SQL 用「都为空或相等」匹配，因此调用方必须把空串转成 null 再传进来。</p>
 *
 * <p>它不负责什么：不做业务校验、不控制事务边界（事务由领域服务的注解决定）、
 * 不发事件、不处理上下文缓存。</p>
 */
public interface ISessionRepository {

    /**
     * 插入一条新会话。
     *
     * <p>所有初始字段（状态、检索开关、两个版本号、最后活跃时间）都由领域服务在实体上设好，
     * 这里只负责写进去。返回影响行数，正常应为 1。</p>
     */
    int insertSession(ChatSessionEntity session);

    /**
  * 按租户 + 用户 + 会话编号读一条会话，是所有会话操作的权限闸门。
     *
     * <p>查不到返回 null，领域服务据此统一抛「会话不存在」——刻意不区分
     * 「真的没有」和「不是你的」，避免有人靠错误差异探测别人的会话是否存在。</p>
     *
   * <p>不加锁，适合只读校验；要改会话状态时必须用下面的加锁版本。</p>
     */
    ChatSessionEntity querySession(String tenantId, String userId, String sessionId);

    /**
     * 按同样的复合范围读会话，并在数据库层对这一行加行锁（SELECT ... FOR UPDATE）。
   *
     * <p>为什么需要加锁：追加消息要「读最大序号再加一」，更新检索策略要「比版本再更新」，
     * 删除会话要阻止期间产生新运行。这些都是先读后写，不加锁时两个并发请求会读到同样的旧值，
     * 结果就是序号撞车或后一次更新把前一次悄悄覆盖。</p>
     *
     * <p>锁会持续到当前事务提交或回滚，所以调用方必须在事务里用它。
     * 项目里统一约定「先锁会话，再操作其他资源」的固定顺序，以避免和其他流程互相死锁。</p>
     */
    ChatSessionEntity lockSession(String tenantId, String userId, String sessionId);

    /**
     * 分页列出某个用户的会话，按最后活跃时间倒序，供前端会话列表使用。
     *
     * <p>翻页用「时间 + 会话编号」双字段游标而不是 offset：会话的活跃时间会不断变化，
     * offset 翻页会漏会话或重复显示；带上会话编号是为了打破时间相同时的并列，保证顺序确定。</p>
     *
  * <p>两个游标参数为空表示取第一页。只返回未删除的会话。</p>
     */
    List<ChatSessionEntity> querySessions(String tenantId, String userId, LocalDateTime cursorTime,
String cursorSessionId, int limit);

    /**
     * 刷新会话的最后活跃时间。
     *
   * <p>每保存一条消息都会跟着调一次，和消息写入在同一个事务里提交。
     * 不刷新的后果是：刚聊过的会话不会浮到列表顶部，用户以为消息没发出去。</p>
  */
    int updateLastMessageTime(String tenantId, String userId, String sessionId, LocalDateTime lastMessageTime);

    /**
  * 用乐观锁的方式更新会话的检索策略。
     *
     * <p>SQL 的 WHERE 里除了身份条件，还要求当前版本号等于 expectedRevision，
   * 更新时把版本号自增。这样两个标签页同时改设置时只有一个能成功，另一个影响行数为 0，
     * 会被领域服务翻译成「请刷新后重试」，而不是默默把对方刚做的修改盖掉。</p>
     *
     * @param tenantId 租户编号，隔离条件，个人模式为 null
     * @param userId 会话拥有者，越权闸门
     * @param sessionId 目标会话
     * @param ragMode 新的检索模式：OFF / AUTO / MANUAL，这是真正生效的策略
     * @param enabled 与模式对应的布尔视图，只为兼容旧接口和旧前端而保留
     * @param expectedRevision 更新前读到的版本号，用于并发冲突检测
     * @return 影响行数；0 表示版本已被别人推进，本次更新未生效
     */
    int updateRagPolicy(String tenantId, String userId, String sessionId, String ragMode,
                        String ragInvocationMode, boolean enabled, long expectedRevision);

    /**
     * 取这段会话当前最大的消息序号，用来给下一条消息算序号。
     *
     * <p>它统计的是全部消息（包括已失效的）。为什么不排除失效消息：序号必须只增不减，
   * 否则撤回一轮之后新消息会复用旧序号，历史顺序和上下文范围判断就全乱了。</p>
     *
     * <p>会话还没有任何消息时返回 null，调用方据此从 1 开始。
  * 必须在锁住会话之后调用，否则并发下两条消息会拿到相同序号。</p>
     */
    Integer queryMaxSequenceNo(String tenantId, String userId, String sessionId);

    /**
     * 取这段会话最大的「有效」消息序号，作为上下文可见范围的上界。
     *
     * <p>与上一个方法的区别正是要点：这个只看 active 消息。上下文装配用它来划定
     * 「模型能看到到第几号为止」，被取消或被覆盖的消息不会因为占了较大的序号
     * 而把可见范围虚假地撑大。</p>
     */
    Integer queryMaxValidSequenceNo(String tenantId, String userId, String sessionId);

    /**
     * 插入一条消息。
     *
     * <p>消息编号、序号、token 估算值都由领域服务在实体上准备好。
     * 它和刷新会话活跃时间必须在同一事务里，否则可能出现消息存了但列表排序没变。</p>
   */
    int insertMessage(ChatMessageEntity message);

    /**
     * 把会话的上下文版本号往前推一格，并返回推进后的新值。
     *
     * <p>什么时候推进：取消运行、消息失效、删除会话、完成一次上下文压缩。
     * 版本一变，之前缓存的上下文和摘要就全部作废，下一轮必须重新装配，
     * 从而杜绝「已经被撤回的内容仍然被当成有效历史喂给模型」。</p>
     *
     * <p>调用前应先锁住会话，确保并发推进不会得到重复的版本号。</p>
     */
    long incrementContextRevision(String tenantId, String userId, String sessionId);

    /**
     * 把某次运行产生的消息批量标记为失效，并记下原因和时间。
     *
     * <p>这是「取消」和「重新生成」的落地动作：不删记录，只标失效。
 * 之后所有上下文装配、历史查询、分享导出都只读 active 的消息，
     * 被取消的半截回答就自动退出对话，但记录仍留着可供审计。</p>
     *
   * <p>返回被标记的消息条数；返回 0 说明这次运行本来就没产生消息，属于正常情况。</p>
     */
    int invalidateRunMessages(String tenantId, String userId, String sessionId, String runId, String reason,
  LocalDateTime invalidatedAt);

    /**
     * 查出某次运行产生的所有消息（不限有效性）。
     *
     * <p>用于运行详情展示和排查：需要看到这次运行到底写了什么，包括已经被标记失效的内容。</p>
     */
    List<ChatMessageEntity> queryRunMessages(String tenantId, String userId, String sessionId, String runId);

    /**
     * 按完整复合范围读一条仍然有效的消息。
     *
     * <p>用于「查看某条消息的引用出处」这类单条操作。身份条件写在 SQL 里，
     * 所以拿到别人的 messageId 也读不到内容；已失效的消息同样读不到，
     * 避免用户点开一条已被撤回的回答还能看到它的引用。</p>
     */
  ChatMessageEntity queryValidMessage(String tenantId, String userId, String sessionId, String messageId);

    /**
     * 按序号正序读出这段会话全部有效消息。
     *
     * <p>上下文装配和分享导出都用它，因此「排除失效消息」这一点至关重要：
     * 少排除一次，被用户撤回的内容就会重新出现在模型上下文或分享出去的文档里。</p>
     *
     * <p>注意它不分页，消息很多的会话会一次读回大量数据，
     * 所以只在确实需要全量（装上下文、导出）时使用，界面翻页请用下面的游标版本。</p>
     */
    List<ChatMessageEntity> queryValidMessages(String tenantId, String userId, String sessionId);

    /**
     * 从指定序号往前翻页读有效消息，按序号倒序返回，供前端「加载更多历史」使用。
  *
 * <p>beforeSequence 传当前已加载的最小序号，为空表示从最新一条开始。
     * 用序号游标而不是 offset，是因为消息只会追加，序号单调，翻页结果稳定不会重复或漏。</p>
     */
    List<ChatMessageEntity> queryValidMessagesBefore(String tenantId, String userId, String sessionId,
          Integer beforeSequence, int limit);

 /**
     * 软删除会话：把状态改成 deleted，记录和消息都保留。
     *
     * <p>条件里带完整身份且要求当前是 active，所以返回行数不等于 1 就说明
     * 会话不存在、不属于当前用户，或已经被并发删除，调用方应据此报错而不是当作成功。</p>
     *
     * <p>删除只是入口收敛的最后一步；取消运行、撤销分享、作废上下文都在这之前由
     * {@code SessionLifecycleService} 完成，否则会留下仍在跑的运行和仍然可用的分享链接。</p>
     */
    int softDelete(String tenantId, String userId, String sessionId);
}
