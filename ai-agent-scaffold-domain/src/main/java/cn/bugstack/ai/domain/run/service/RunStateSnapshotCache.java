package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 运行状态的极短寿命只读快照缓存，用来给「高频、只读、不产生副作用」的状态检查挡掉大量数据库查询。
 *
 * <p>解决什么问题：模型每吐一小段内容、每准备调一次工具，都要问一句「这次运行还能继续吗」。
 * 一轮对话下来可能问上百次，每次都查库会把数据库打满。但缓存运行状态又很危险：
 * 缓存久了会读到「用户已经取消，这里还以为在跑」。折中办法是把有效期压到最多 200 毫秒，
 * 既削掉了绝大部分重复查询，又保证取消最迟 200 毫秒就能被看到。</p>
 *
 * <p>关键边界：凡是会产生不可撤销外部副作用的地方（真的发 HTTP、真的发邮件）都不许用这份快照，
 * 必须改走数据库行锁读。这份缓存只服务于「先粗筛一遍」的场景。</p>
 *
 * <p>所属层次：领域层运行服务的内部实现，包级可见，只给 {@code RunControlService} 用，
 * 不对外暴露，因此不需要考虑被别的模块滥用。</p>
 *
 * <p>它不负责什么：不写数据库、不做状态机判断、不管事务。缓存什么时候作废由调用方在事务提交后显式通知。</p>
 */
final class RunStateSnapshotCache {

    /** 快照最长存活 200 毫秒（纳秒表示）；这个上限是取消可见延迟的天花板，构造时不允许配得更长。 */
    static final long DEFAULT_TTL_NANOS = 200_000_000L;
  /** 单实例最多缓存的运行条数；有上限才不会因为并发运行暴涨而把内存吃掉。 */
    static final int DEFAULT_MAX_ENTRIES = 2_048;
    /** 回源互斥锁的分片数；按键哈希分到 64 把锁上，让不同运行能并发回源，只有同一运行才互相等待。 */
    private static final int STRIPE_COUNT = 64;
    /** 摊销清理的触发掩码；读计数每凑满 64 次做一次过期清理，避免每次读都去扫全表。 */
    private static final long CLEANUP_MASK = 63L;

    /** 本实例实际使用的有效期；生产走默认值，单测可传更小值来验证过期行为。 */
    private final long ttlNanos;
    /** 本实例实际使用的容量上限；超过后按创建时间淘汰最老的条目。 */
    private final int maxEntries;
    /** 可替换的单调时钟；用单调时钟而不是墙上时间，避免系统时间被回调导致快照永不过期。 */
    private final LongSupplier nanoTime;
    /** 快照主存储，键含租户、用户、运行三段，保证不同租户即使运行编号相同也读不到彼此的状态。 */
    private final ConcurrentHashMap<RunKey, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** 分片锁数组；同一个运行的并发回源只允许一个线程真正查库，其余线程等它填好后直接复用。 */
    private final Object[] stripes = new Object[STRIPE_COUNT];
    /** 容量淘汰与批量过期清理的全局锁；这两件事会遍历整表，必须串行，不能和分片锁混用。 */
    private final Object maintenanceMonitor = new Object();
    /** 读取次数计数器；只用来决定什么时候顺手清一次过期条目，不参与任何业务判断。 */
    private final AtomicLong reads = new AtomicLong();

  /**
     * 按生产默认参数创建缓存：200 毫秒有效期、2048 条上限、系统单调时钟。
     */
    RunStateSnapshotCache() {
        // 直接转交全参构造器，保证生产与测试走同一套初始化逻辑。
        this(DEFAULT_TTL_NANOS, DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    /**
     * 供测试注入可控参数的构造器。
     *
   * <p>有效期被硬性限制在默认上限之内：允许把 TTL 调长就等于允许取消延迟变长，这是不可接受的，
  * 所以非法参数直接抛异常而不是悄悄纠正。</p>
     *
   * @param ttlNanos   快照有效期，必须大于 0 且不超过 200 毫秒
     * @param maxEntries 容量上限，必须大于 0
     * @param nanoTime   单调时钟来源，测试用它精确控制过期时刻
     */
    RunStateSnapshotCache(long ttlNanos, int maxEntries, LongSupplier nanoTime) {
        // 参数非法就直接失败，绝不允许通过配置把取消可见延迟拉长。
        if (ttlNanos <= 0 || ttlNanos > DEFAULT_TTL_NANOS || maxEntries <= 0) {
            // 抛非法参数异常让装配阶段就暴露问题，而不是等到线上取消失灵。
            throw new IllegalArgumentException("运行状态快照参数不合法");
        }
        // 记下本实例的有效期，后续每次写入都用它算到期时间。
        this.ttlNanos = ttlNanos;
     // 记下容量上限，写入时据此淘汰最老条目。
        this.maxEntries = maxEntries;
      // 记下时钟来源，所有到期判断都以它为准。
        this.nanoTime = nanoTime;
        // 预先把分片锁全部实例化，避免运行期再判空造成竞态。
        for (int i = 0; i < stripes.length; i++) {
       // 每个分片是一个独立的互斥对象，仅用于同键回源串行化。
            stripes[i] = new Object();
        }
    }

    /**
     * 读取一次运行的状态快照，缓存未命中或已过期时回源查库。
     *
     * <p>各层职责：
     * 第一层：无锁快路径。命中且未过期就直接返回，绝大多数高频调用都停在这一层，不用抢锁也不用查库。
     * 第二层：按键取分片锁。只让同一个运行的回源串行，不同运行仍可并行查库，避免全局排队。
     * 第三层：进锁后再查一次缓存。等锁期间别的线程可能已经填好了，这次复查能省掉一次重复查库。
     * 第四层：真正回源。调用方传进来的加载器去查数据库，结果（包括「查不到」）都要缓存，
     *  否则不存在的 runId 会被反复查库形成穿透。
     * 第五层：有界写入并顺手清理过期条目，防止内存无上限增长。</p>
     *
 * <p>数据流：
     * 租户 + 用户 + 运行编号
     * → 组装隔离键
 * → 读缓存（命中且未过期则直接返回）
     * → 取分片锁
     * → 复查缓存
     * → 调用加载器查数据库
     * → 截取状态与上下文版本生成快照
     * → 有界写入缓存
     * → 返回快照</p>
     *
     * @param loader 回源加载器，通常就是「按可信身份查运行记录」，返回 null 表示运行不存在
  * @return 不可变快照，一定非空；其中 run 为 null 表示这次运行确实不存在
     */
    Snapshot get(String tenantId, String userId, String runId, Supplier<ChatRunEntity> loader) {
        // 组装带租户与用户的复合键，防止不同租户之间互相读到状态。
        RunKey key = key(tenantId, userId, runId);
        // 取一次当前时刻，作为后面所有到期判断的基准，避免同一次调用里时间前后不一致。
        long now = nanoTime.getAsLong();
      // 第一层：先走无锁快路径读缓存。
        Snapshot current = snapshots.get(key);
        // 命中且还没过期，直接复用，这是最常见的高频路径。
        if (current != null && current.expiresAtNanos() > now) {
        // 顺手做一次摊销清理，把别的键留下的过期垃圾捎带清掉。
            cleanupSometimes(now);
            // 返回缓存中的快照，本次调用完全不碰数据库。
            return current;
        }
        // 第二层：未命中或已过期，取该键对应的分片锁准备回源。
        Object stripe = stripe(key);
        // 同一运行只允许一个线程回源数据库，其他键仍可并发加载。
        synchronized (stripe) {
 // 等锁期间时间已经推进，重新取一次时刻再判断。
            now = nanoTime.getAsLong();
      // 第三层：复查缓存，等锁期间别人可能已经填好了。
            current = snapshots.get(key);
// 复查命中且未过期就直接复用，省掉一次重复查库。
            if (current != null && current.expiresAtNanos() > now) {
         // 同样顺手摊销清理一次。
                cleanupSometimes(now);
   // 返回别的线程刚填进去的快照。
                return current;
            }
 // 复查发现的是过期条目，用带值比较的方式移除，避免误删别人刚写入的新快照。
            if (current != null) {
         // 只有当前值仍是那条过期快照时才删，防止把新值删掉。
                snapshots.remove(key, current);
            }
            // 第四层：真正查库；查不到会返回 null，这个「不存在」的结论同样要缓存以防穿透。
            ChatRunEntity loaded = loader.get();
   // 查库本身耗时，到期时间要以查完的时刻起算，否则快照实际可用时长会被压缩。
            long loadedAt = nanoTime.getAsLong();
     // 只截取状态检查真正需要的字段，并算出到期时刻。
            Snapshot loadedSnapshot = snapshot(loaded, loadedAt + ttlNanos);
    // 第五层：有界写入，必要时淘汰最老条目。
            putBounded(key, loadedSnapshot, loadedAt);
            // 写完再摊销清理一次，保持整表规模稳定。
            cleanupSometimes(loadedAt);
       // 返回刚回源得到的快照。
            return loadedSnapshot;
        }
    }

    /**
     * 立刻作废某次运行的快照，让下一次读取一定回源。
     *
  * <p>状态一旦发生迁移（取消、完成、抬高上下文版本）就必须调它，否则最多 200 毫秒内
 * 还会有执行点读到旧状态继续干活。调用方要在事务提交之后才调，
  * 否则事务回滚时缓存里的旧事实已经被删，反而会读回同一个旧值白跑一趟。</p>
     */
    void invalidate(String tenantId, String userId, String runId) {
        // 用同样的规则组装隔离键，保证删到的是同一条。
        RunKey key = key(tenantId, userId, runId);
        // 与回源共用分片锁，避免「删除」和「正在写入」交错导致刚删完又被旧值填回来。
        synchronized (stripe(key)) {
            // 直接摘除条目，下一次读取必然回源查库。
            snapshots.remove(key);
        }
    }

    /**
     * 把快照里的运行对象再复制一份交给调用方。
   *
     * <p>缓存里的对象是多个线程共享的，如果直接返回引用，任何调用方的一次 set 都会污染别人读到的状态。
     * 所以这里再复制一次，缓存永远只被本类修改。</p>
     *
     * @return 复制出的运行实体；快照里本来就没有运行（不存在的 runId）时返回 null
     */
    ChatRunEntity materialize(Snapshot snapshot) {
   // 运行不存在就如实返回空，让调用方按「无权访问」处理。
        return snapshot.run() == null ? null : copy(snapshot.run());
    }

    /** 返回当前缓存条目数，仅供容量与淘汰行为的测试断言使用，不参与业务判断。 */
    int size() {
   // 直接透出底层容器规模，含尚未被清理的过期条目。
        return snapshots.size();
    }

    /**
     * 在全局维护锁内写入一条快照，必要时先清过期、再淘汰最老条目。
     *
     * <p>为什么要串行：清理和淘汰都要遍历整表，多个线程同时做会互相把对方选中的「最老条目」删掉，
     * 导致实际容量控制失效。为什么先清过期再淘汰：过期条目本来就该走，优先清它们能避免误伤还在用的新条目。</p>
     */
    private void putBounded(RunKey key, Snapshot value, long now) {
        // 容量控制必须整表串行，否则并发淘汰会互相干扰。
        synchronized (maintenanceMonitor) {
      // 先把已经到期的条目清掉，很多时候清完就不需要淘汰了。
            removeExpired(now);
// 只有新增键才可能撑破容量；覆盖已有键不会让条目数变多。
            if (!snapshots.containsKey(key)) {
  // 清完还是满，就按创建时间从最老的开始淘汰，直到腾出位置。
                while (snapshots.size() >= maxEntries) {
     // 选出创建时刻最早的键作为淘汰对象，近似 FIFO 而不是 LRU，实现简单且足够。
                    RunKey oldest = snapshots.entrySet().stream()
                            .min(Comparator.comparingLong(entry -> entry.getValue().createdAtNanos()))
                            .map(java.util.Map.Entry::getKey).orElse(null);
           // 表已经空了却还没腾出位置，说明容量配置过小，跳出避免死循环。
                    if (oldest == null) {
            // 停止淘汰，本次写入允许短暂超出上限。
                        break;
                    }
                 // 摘掉最老条目；被淘汰的运行下次读取会重新回源，不会读到错误状态。
                    snapshots.remove(oldest);
                }
            }
      // 写入本次回源结果，供后续 200 毫秒内的高频读取复用。
            snapshots.put(key, value);
        }
    }

    /**
  * 摊销式过期清理：每 64 次读取才真正扫一遍表。
     *
     * <p>不这么做的话，只写不读的过期条目会一直留在内存里；而每次读都扫表又太贵。
     * 用读计数按位取模来触发，成本被摊到多次调用上。</p>
     */
    private void cleanupSometimes(long now) {
        // 读计数每凑满 64 次触发一次清理，其余 63 次直接返回，几乎无开销。
        if ((reads.incrementAndGet() & CLEANUP_MASK) == 0) {
            // 清理要遍历整表，必须拿全局维护锁串行执行。
            synchronized (maintenanceMonitor) {
           // 把所有到期条目一次性删掉。
                removeExpired(now);
            }
        }
    }

    /** 删除所有到期快照；必须在持有维护锁的前提下调用，否则会与容量淘汰互相干扰。 */
    private void removeExpired(long now) {
        // 到期时刻小于等于当前时刻即视为失效，条件遍历删除。
        snapshots.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    /** 按键哈希挑一把分片锁；取绝对值再取模，避免哈希为负数时下标越界。 */
    private Object stripe(RunKey key) {
   // 同一个键必然落到同一把锁上，这是「同键串行、异键并行」的实现基础。
        return stripes[(key.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    /** 组装缓存键；空白租户统一归一成 null，防止 "" 和 null 被当成两个不同租户产生重复条目。 */
    private RunKey key(String tenantId, String userId, String runId) {
        // 租户为空白时归一化，保证同一份数据只有一个缓存键。
        return new RunKey(tenantId == null || tenantId.isBlank() ? null : tenantId, userId, runId);
    }

    /**
     * 把数据库读出的运行记录裁成只读快照。
     *
     * <p>额外把状态和上下文版本单独拎出来，是因为门禁判断只需要这两个字段，
  * 调用方无需再复制整个实体就能完成判断，只有确实要用完整实体时才走复制。</p>
   */
    private Snapshot snapshot(ChatRunEntity run, long expiresAtNanos) {
        // 由到期时刻反推创建时刻，容量淘汰按它排序，避免再多存一个字段。
        long createdAtNanos = expiresAtNanos - ttlNanos;
        // 运行不存在也要生成快照并缓存，否则不存在的编号会被反复查库形成缓存穿透。
        return run == null
                ? new Snapshot(null, null, null, createdAtNanos, expiresAtNanos)
                : new Snapshot(copy(run), run.getStatus(), run.getCurrentContextRevision(), createdAtNanos,
                expiresAtNanos);
    }

    /**
 * 逐字段复制运行记录，并把可变集合也拷一份。
     *
     * <p>关键在 RAG 绑定清单：如果直接把原列表引用放进缓存，某个调用方对它做一次修改，
     * 后面所有读到这份快照的人看到的检索范围都会跟着变。所以这里用不可变副本，
     * 顺便把 null 归一成空列表，免得每个使用方都要判空。</p>
     */
    private ChatRunEntity copy(ChatRunEntity run) {
        // 按字段重建实体，只搬数据不共享任何可变引用。
        return ChatRunEntity.builder().runId(run.getRunId()).turnId(run.getTurnId()).tenantId(run.getTenantId())
                .userId(run.getUserId()).sessionId(run.getSessionId()).sourceType(run.getSourceType())
                .sourceId(run.getSourceId()).ragEnabled(run.getRagEnabled()).ragMode(run.getRagMode())
                .ragInvocationMode(run.getRagInvocationMode())
                .ragPolicyRevision(run.getRagPolicyRevision())
                .ragBindingIds(run.getRagBindingIds() == null ? List.of() : List.copyOf(run.getRagBindingIds()))
                .traceId(run.getTraceId())
                .status(run.getStatus()).version(run.getVersion())
                .baseContextRevision(run.getBaseContextRevision())
                .currentContextRevision(run.getCurrentContextRevision()).predecessorRunId(run.getPredecessorRunId())
                .successorRunId(run.getSuccessorRunId()).userMessageId(run.getUserMessageId())
                .steerInstruction(run.getSteerInstruction()).terminalReason(run.getTerminalReason())
                .cancelRequestedAt(run.getCancelRequestedAt()).startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt()).build();
    }

    /**
     * 不可变的运行状态快照。
     *
     * <p>run 为 null 表示这次运行确实不存在（这个结论也要缓存，防止穿透）；
   * status 和 contextRevision 是从 run 里提前拎出来的热字段，门禁判断只看它们；
     * 两个时间戳都用单调时钟纳秒值，分别用于容量淘汰排序和过期判断。</p>
     */
    record Snapshot(ChatRunEntity run, RunStatus status, Long contextRevision, long createdAtNanos,
                    long expiresAtNanos) {
    }

    /**
     * 缓存键，把租户、用户、运行三段一起作为身份。
     *
     * <p>必须带租户和用户：只用 runId 做键的话，一旦编号被猜到或伪造，
     * 就可能读到别的租户的运行状态，等于绕过了整条隔离链。</p>
     */
    private record RunKey(String tenantId, String userId, String runId) {
    }
}
