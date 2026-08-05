package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「每次调用大模型时，到底往上下文里塞了哪些 RAG 资料」的进程内证据仓。
 *
 * <p>解决什么问题：一次对话可能连续调用模型多次（工具调用、多轮反思、工作流多节点），每次注入的资料都不一样。
 * 等回答产出后要校验引用真假，必须知道这一整轮运行累计注入过哪些资料。
 * 把它挂在请求线程上传不下去（异步、回调、多节点都会断链），写库又太重（每次模型调用都写一次库不现实），
 * 所以这里用一个按「租户 + 用户 + 会话 + 运行」四元隔离的内存表来承接。</p>
 *
 * <p>属于哪一层：领域层（domain）的进程内状态服务，Spring 单例，被并发请求共享，因此全部用并发容器实现。</p>
 *
 * <p>谁会调用它：上下文注入插件（ContextInjectionPlugin）在每次调模型前 record；
 * 对话服务在回答产出后 snapshot 取白名单交给引用校验器；运行结束后 clear 释放内存。</p>
 *
 * <p>它向下调用什么：什么都不调用，纯内存操作。</p>
 *
 * <p>它不负责什么：不持久化（进程重启数据全丢，这是可接受的——运行结束证据就没用了）、
 * 不做跨机器共享（同一次运行必须落在同一台机器上，这由运行调度保证）、不判断引用真假、不做检索、
 * 不做租户鉴权（传进来的四元身份必须已经是可信的）。</p>
 *
 * <p>为什么处处设上限：这是常驻内存的表，只要有一个环节忘了 clear，或者有人构造异常请求疯狂发起运行，
 * 内存就会一直涨到进程崩溃。所以作用域数量、单次运行的调用次数、单次调用的引用条数、存活时间四个维度都设了硬上限，
 * 超限直接抛错而不是静默丢弃——静默丢弃会让引用校验拿到不完整的白名单，把合法引用误判成伪造。</p>
 */
@Service
public class RagInvocationEvidenceStore {
    /**
     * 同时最多保存多少个运行作用域，防止内存无界增长。
     *
     * <p>触顶后新的运行会直接被拒绝（已有作用域的写入不受影响）。宁可拒绝新运行，
     * 也不驱逐正在跑的运行的证据——被驱逐的那次运行会在校验阶段把合法引用误判成伪造。</p>
     */
    private static final int MAX_SCOPES = 2_000;
    /**
     * 单次运行最多允许记录多少次模型调用的证据。
     *
     * <p>防止一个陷入死循环的工具调用链把内存吃光。正常对话远达不到 128 次，触顶通常意味着上层逻辑失控。</p>
     */
    private static final int MAX_INVOCATIONS_PER_SCOPE = 128;
    /**
     * 单次模型调用最多允许携带多少条引用。
     *
     * <p>既在写入前校验单批数量，也在合并后校验累计数量。上限存在的意义是：
     * 一次注入几百条引用说明检索预算或分块配置出了问题，此时直接失败比默默截断更安全，
     * 因为截断会让模型引用了却在白名单里找不到，最后被误判为编造。</p>
     */
    private static final int MAX_CITATIONS_PER_INVOCATION = 128;
    /**
     * 作用域存活时间，30 分钟。
     *
     * <p>兜底用：正常路径下运行结束会主动 clear，但异常退出、进程被打断、上层忘记调用都可能留下垃圾。
     * 每次写入前会顺手清掉超过这个时间没更新过的作用域，保证长期运行的进程内存不会只增不减。</p>
     */
    private static final long TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    /**
     * 全部作用域的存储表，键是「租户 + 用户 + 会话 + 运行」四元组，值是该次运行的全部证据。
     *
     * <p>用四元组而不是只用运行编号做键，是为了在内存里也保持租户和用户隔离：
     * 即使运行编号被猜中或碰撞，也读不到别人的证据。</p>
     *
     * <p>用并发映射是因为本类是 Spring 单例，会被多个请求线程同时读写；
     * 只存在内存中，不持久化，进程重启即清空。</p>
     */
    private final ConcurrentHashMap<Scope, ScopeEvidence> scopes = new ConcurrentHashMap<>();

    /**
     * 登记一次模型调用实际注入了哪些 RAG 资料。
     *
     * <p>各层职责：
     * 第一层：校验四元身份和调用编号，任何一项缺失都会破坏隔离，直接拒绝；
     * 第二层：把证据冻结成不可变副本，防止调用方事后改动已登记的内容；
     * 第三层：核对单次调用的引用条数上限；
     * 第四层：顺手清理过期作用域，并检查作用域总数上限；
     * 第五层：原子地写入该作用域（作用域不存在则新建），同一个调用编号重复登记时合并证据。</p>
     *
     * <p>数据流：
     * 四元身份 + 模型调用编号 + 本次注入的证据
     * → 身份与编号校验
     * → 证据冻结成不可变副本
     * → 单次引用条数上限校验
     * → 清理过期作用域
     * → 作用域总数上限校验
     * → compute 原子写入（新建或复用作用域，按调用编号合并证据）
     * → 刷新该作用域的存活时间</p>
     *
     * <p>会修改进程内状态，但不写数据库、不发事件。主要失败条件：身份或调用编号为空、
     * 单次引用超上限、作用域总数已满、同一调用编号的证据自相矛盾、单次运行调用次数超上限。</p>
     *
     * <p>失败时刻意抛异常而不是静默跳过：证据仓不完整会直接导致后续引用校验把真引用判成假引用。</p>
     */
    public void record(String tenantId, String userId, String sessionId, String runId, String invocationId,
                       List<RagContextEvidence> evidence) {
        // 第一层：先把四元身份收敛成作用域键，构造过程本身就会校验四项都不为空。
        Scope scope = Scope.of(tenantId, userId, sessionId, runId);
        // 模型调用编号也必须有值，它是同一次运行内区分多次调用的唯一依据；为空就会把不同次调用的证据糊在一起。
        requireText(invocationId, "模型调用ID");
        // 第二层：立刻冻结成不可变副本。调用方后续若复用并修改自己那个列表，已登记的证据不能跟着变，
        // 否则校验时用的白名单和当时真正注入的内容就不一致了。null 按空处理，表示这次没注入资料。
        List<RagContextEvidence> safe = evidence == null ? List.of() : List.copyOf(evidence);
        // 数一数这次总共带了多少条引用；对 null 元素按 0 计，容忍上游塞进脏数据。
        int count = safe.stream().mapToInt(item -> item == null ? 0 : item.citations().size()).sum();
        // 第三层：超过单次上限直接失败。不截断，因为截断后模型引用的资料会在白名单里缺失，被误判成编造。
        if (count > MAX_CITATIONS_PER_INVOCATION) throw new IllegalStateException("单次模型调用RAG引用超过上限");
        // 第四层：借这次写入的机会清掉过期作用域。惰性清理不需要额外的定时线程，
        // 又能保证只要还有流量，内存就会被持续回收。
        evictExpired();
        // 作用域总数上限只对「新建作用域」生效：已有作用域继续写入不受影响，避免把正在跑的运行卡死在半途。
        if (!scopes.containsKey(scope) && scopes.size() >= MAX_SCOPES) throw new IllegalStateException("RAG运行证据仓已满");
        // 第五层：用 compute 做原子写入，保证并发下同一个作用域不会被两个线程各建一份、互相覆盖。
        scopes.compute(scope, (key, current) -> {
            // 作用域首次出现就新建一个容器，否则复用已有的，实现「同一次运行的多次调用累积在一起」。
            ScopeEvidence value = current == null ? new ScopeEvidence() : current;
            // 按调用编号写入证据；同一编号重复登记时内部会做合并与冲突检查，并顺带刷新存活时间。
            value.put(invocationId, safe);
            // 把容器放回映射（新建时是写入，已存在时是原样放回），compute 借此完成原子更新。
            return value;
        });
    }

    /**
     * 取出当前这次运行累计注入过的全部证据，作为引用校验的白名单。
     *
     * <p>返回的是稳定快照：按模型调用编号排序，所以同一次运行反复取会得到完全一致的顺序，便于比对和测试。</p>
     *
     * <p>作用域不存在（没走过 RAG、已被清理或已过期）时返回空列表而不是抛错，
     * 因为「这次运行没有注入任何资料」是完全正常的情形。</p>
     *
     * <p>只读，不修改状态。</p>
     */
    public List<RagContextEvidence> snapshot(String tenantId, String userId, String sessionId, String runId) {
        // 按四元身份找作用域；构造键时会顺带校验身份完整性，身份不全会直接抛错而不是返回别人的数据。
        ScopeEvidence value = scopes.get(Scope.of(tenantId, userId, sessionId, runId));
        // 没有作用域就返回空白名单：这次运行没注入过资料，后续任何引用都会被判为编造，符合预期。
        return value == null ? List.of() : value.snapshot();
    }

    /**
     * 取出某一次具体模型调用注入的证据。
     *
     * <p>用于按单次调用做归因，例如排查「第三次工具调用后模型为什么引用错了」。
     * 作用域或调用编号不存在时返回空列表，不抛错。</p>
     *
     * <p>只读，不修改状态。</p>
     */
    public List<RagContextEvidence> snapshotInvocation(String tenantId, String userId, String sessionId,
                                                       String runId, String invocationId) {
        // 同样先按四元身份定位作用域，隔离口径与运行级快照完全一致。
        ScopeEvidence value = scopes.get(Scope.of(tenantId, userId, sessionId, runId));
        // 作用域不存在返回空；存在则再按调用编号取那一次的证据。
        return value == null ? List.of() : value.snapshotInvocation(invocationId);
    }

    /**
     * 运行结束后清掉这次运行的全部证据，释放内存。
     *
     * <p>这是正常路径下回收内存的主要手段（过期淘汰只是兜底）。运行进入终态后证据就没有用途了，
     * 留着既占内存又可能被后续请求误读。</p>
     *
     * <p>幂等：作用域本来就不存在时什么也不做。会修改进程内状态，但不写库。</p>
     *
     * <p>注意调用时机必须在引用校验之后：清早了，校验就拿不到白名单，会把真引用判成假的。</p>
     */
    public void clear(String tenantId, String userId, String sessionId, String runId) {
        // 直接按四元身份移除整个作用域，连带它下面所有模型调用的证据一起释放。
        scopes.remove(Scope.of(tenantId, userId, sessionId, runId));
    }

    /**
     * 惰性淘汰过期作用域，控制长期运行进程的内存占用。
     *
     * <p>在每次写入前调用，所以不需要额外的定时线程；只要还有流量，垃圾就会被持续清掉。</p>
     *
     * <p>判断依据是作用域最后一次更新时间：一个运行如果 30 分钟没有再注入过资料，
     * 基本可以断定它已经结束或已经异常中断了。会修改进程内状态。</p>
     */
    private void evictExpired() {
        // 算出过期时间线：早于这个时刻最后更新的作用域都视为已经结束或已经被中断。
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        // 遍历移除所有过期作用域。并发映射的迭代器是弱一致的，允许边遍历边被别的线程写入，不会抛并发修改异常。
        scopes.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
    }

    /**
     * 校验作用域相关的标识字段非空。
     *
     * <p>四元身份和模型调用编号任一为空都会让证据隔离失效：例如租户号为空时，
     * 不同租户的运行可能落进同一个作用域，一个租户就能读到另一个租户注入过什么资料。</p>
     */
    private static void requireText(String value, String name) {
        // null 和空白串都判为非法，直接抛错；宁可让这次登记失败，也不能生成一个隔离不完整的作用域键。
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    /**
     * 一次运行的复合键：租户、用户、会话、运行四项缺一不可。
     *
     * <p>为什么不只用运行编号：内存表和数据库不一样，没有租户过滤这层保护，
     * 只要键能撞上就能读到内容。把四项都放进键里，等于把租户和用户隔离直接编码进查找路径。</p>
     *
     * <p>record 自动生成的 equals 和 hashCode 保证四项完全相同才算同一个作用域。</p>
     */
    private record Scope(String tenantId, String userId, String sessionId, String runId) {
        /**
         * 校验四项标识后创建作用域键。
         *
         * <p>所有读写入口都必须经它构造键，这样「身份必须完整」这条规则只写一处，不会有哪个入口漏掉校验。</p>
         */
        private static Scope of(String tenantId, String userId, String sessionId, String runId) {
            // 逐项校验租户号和用户号：这两项决定了跨租户、跨用户的隔离边界。
            requireText(tenantId, "租户ID"); requireText(userId, "用户ID");
            // 再校验会话号和运行号：这两项决定了同一个用户的不同对话、不同运行之间不会互相看到证据。
            requireText(sessionId, "会话ID"); requireText(runId, "运行ID");
            // 四项都合法才生成键；到这里可以确定这个键一定指向一个隔离完整的作用域。
            return new Scope(tenantId, userId, sessionId, runId);
        }
    }

    /**
     * 单次运行下，按模型调用编号组织的全部证据。
     *
     * <p>为什么要按调用编号分组：一次运行会多次调模型，每次注入的资料不同。
     * 分组保存既能按单次调用做归因排查，也能在运行结束时汇总成完整白名单。</p>
     *
     * <p>内部同样用并发映射，因为同一次运行的多次模型调用可能发生在不同线程上（异步回调、并行节点）。</p>
     */
    private static final class ScopeEvidence {
        /**
         * 「模型调用编号 → 该次调用注入的证据」映射。
         *
         * <p>并发映射：同一次运行的多次调用可能来自不同线程。数量受单运行调用次数上限约束，
         * 防止失控的工具调用链把内存吃光。只存内存，不持久化。</p>
         */
        private final Map<String, InvocationEvidence> invocations = new ConcurrentHashMap<>();
        /**
         * 本作用域最后一次被写入的时间，用于过期淘汰。
         *
         * <p>声明成 volatile 是因为写入线程和执行淘汰的线程可能不是同一个：
         * 不加 volatile，淘汰线程可能一直读到旧值，把一个还活跃的作用域误当成过期清掉。</p>
         */
        private volatile long updatedAt = System.currentTimeMillis();

        /**
         * 写入某次模型调用的证据；同一个调用编号重复写入时合并而不是覆盖。
         *
         * <p>各层职责：
         * 第一层：新增调用编号前检查单运行调用次数上限；
         * 第二层：原子地把新证据与已有证据合并（合并过程会做冲突和累计上限检查）；
         * 第三层：刷新存活时间，让活跃运行不会被过期淘汰误清。</p>
         *
         * <p>数据流：调用编号 + 本次证据 → 调用次数上限校验 → compute 原子合并 → 刷新更新时间</p>
         *
         * <p>为什么要合并而不是覆盖：同一次模型调用可能分多批注入资料（例如多个上下文投稿人各投一份）。
         * 覆盖会丢掉先写入的那部分，导致白名单缺失、真引用被判成伪造。</p>
         *
         * <p>会修改进程内状态。失败条件：调用次数超上限、合并时发现同一编号对应不同证据、累计引用超上限。</p>
         */
        private void put(String invocationId, List<RagContextEvidence> evidence) {
            // 第一层：只有「新增一个调用编号」时才检查次数上限；已有编号继续追加不受限制，
            // 否则同一次调用的多批注入会在中途被拦掉，反而制造出不完整的白名单。
            if (!invocations.containsKey(invocationId) && invocations.size() >= MAX_INVOCATIONS_PER_SCOPE) {
                // 一次运行调了上百次模型基本可以断定上层逻辑失控，直接失败以保护进程内存。
                throw new IllegalStateException("单次运行模型调用次数超过RAG证据上限");
            }
            // 第二层：compute 原子地把旧证据和新证据合并成一条新的调用记录。
            // 用 compute 而不是先读后写，是为了避免两个线程同时追加时其中一批被覆盖丢失。
            invocations.compute(invocationId, (key, previous) -> new InvocationEvidence(invocationId,
                    merge(previous == null ? List.of() : previous.evidence, evidence)));
            // 第三层：刷新最后更新时间，声明这个作用域还活着，避免正在跑的运行被过期淘汰清掉。
            updatedAt = System.currentTimeMillis();
        }

        /**
         * 合并两批证据，并在合并过程中拒绝任何自相矛盾的数据。
         *
         * <p>各层职责：
         * 第一层：把已有证据和新来的证据接成一条流依次处理；
         * 第二层：按检索批次编号去重，同一个批次编号若对应两份不同证据，直接判为冲突；
         * 第三层：再按引用编号逐条查一遍，同一个引用编号若指向两份不同出处，同样判为冲突；
         * 第四层：核对累计引用条数上限；
         * 第五层：按首次出现顺序输出去重后的证据。</p>
         *
         * <p>数据流：
         * 已有证据 + 新证据
         * → 拼成一条流
         * → 按检索批次编号去重（冲突则抛错）
         * → 按引用编号校验一致性（冲突则抛错）
         * → 累计引用条数上限校验
         * → 按首次出现顺序输出</p>
         *
         * <p>为什么冲突要抛错而不是取其中一份：白名单是判断引用真假的唯一标准。
         * 同一个编号指向两份内容，说明上游生成编号的逻辑有 Bug；此时随便取一份，
         * 就可能把模型如实引用的那一份判成伪造，或者反过来放过真正的伪造。宁可让这次运行失败也不能这样。</p>
         *
         * <p>纯计算，不写库。用保序映射保证输出顺序稳定、结果可复现。</p>
         */
        private List<RagContextEvidence> merge(List<RagContextEvidence> existing,
                                               List<RagContextEvidence> incoming) {
            // 「检索批次编号 → 证据」表，用于按批次去重；保序是为了让合并结果顺序稳定、可比对。
            Map<String, RagContextEvidence> retrievals = new LinkedHashMap<>();
            // 「引用编号 → 出处」表，只用于冲突检查和累计计数，不参与最终输出。
            // 单独建这张表的原因是：批次编号相同不代表内部每条引用都相同，必须再细一层校验。
            Map<String, RagContextEvidence.CitationReference> citations = new LinkedHashMap<>();
            // 第一层：把已有证据和新证据接成一条流，按「先旧后新」的顺序依次处理，
            // 保证先登记的内容在输出里排在前面。
            for (RagContextEvidence item : java.util.stream.Stream.concat(existing.stream(), incoming.stream()).toList()) {
                // 容错：跳过 null 元素，避免一个脏数据让整次运行的证据登记全部失败。
                if (item == null) continue;
                // 第二层：按检索批次编号去重。首次出现时写入并返回 null，重复出现时返回已存在的那份。
                RagContextEvidence previous = retrievals.putIfAbsent(item.retrievalId(), item);
                // 同一个批次编号却带着两份不同的证据，说明上游要么复用了编号，要么把内容改了。
                if (previous != null && !previous.equals(item)) {
                    // 直接抛错终止：白名单一旦不可信，后面的引用校验结论就全都不可信。
                    throw new IllegalStateException("同一检索ID的RAG证据发生冲突");
                }
                // 第三层：即使批次编号相同也要逐条检查引用，防止「批次号一样但内部引用被换掉」这种更隐蔽的冲突。
                for (RagContextEvidence.CitationReference citation : item.citations()) {
                    // 同上，首次出现时登记，重复出现时拿到已存在的那份用于比对。
                    RagContextEvidence.CitationReference prior = citations.putIfAbsent(citation.citationId(), citation);
                    // 同一个引用编号指向了两份不同出处，这是最危险的情形：模型引用它时根本无法判断到底指哪一份。
                    if (prior != null && !prior.equals(citation)) {
                        // 抛错终止，绝不允许一份有歧义的白名单进入引用校验。
                        throw new IllegalStateException("同一引用ID的RAG证据发生冲突");
                    }
                }
            }
            // 第四层：合并后的累计引用条数也要卡上限。单批不超限但多批累加后超限的情况完全可能发生，
            // 所以这里必须再查一次，否则上限就形同虚设。
            if (citations.size() > MAX_CITATIONS_PER_INVOCATION) {
                // 超限直接失败，而不是截断；截断会让白名单不完整，把真引用误判成伪造。
                throw new IllegalStateException("单次模型调用RAG引用累计超过上限");
            }
            // 第五层：按首次出现顺序输出去重后的证据，冻结成不可变列表防止事后被改。
            return List.copyOf(retrievals.values());
        }

        /**
         * 汇总本次运行全部模型调用的证据，生成稳定快照。
         *
         * <p>按模型调用编号排序后依次摊平，所以同一份数据无论取多少次、在哪台机器上取，顺序都完全一致，
         * 便于日志比对和回归测试断言。</p>
         *
         * <p>只读，不修改状态；返回不可变列表，调用方拿到后无法反过来改动证据仓。</p>
         */
        private List<RagContextEvidence> snapshot() {
            // 用一个可写列表承接摊平结果，最后再冻结成只读列表对外发布。
            List<RagContextEvidence> result = new ArrayList<>();
            // 流式汇总：所有调用记录 → 按调用编号排序（这一步决定了快照顺序的确定性）
            // → 逐个把它的证据追加进结果列表。排序而不是直接遍历，是因为并发映射的遍历顺序本身不稳定。
            invocations.values().stream().sorted(Comparator.comparing(InvocationEvidence::invocationId))
                    .forEach(item -> result.addAll(item.evidence));
            // 冻结成不可变列表返回，作为引用校验的白名单；调用方无法通过它反向修改证据仓。
            return List.copyOf(result);
        }

        /**
         * 取出指定模型调用的证据，用于单次调用级别的归因排查。
         *
         * <p>调用编号不存在时返回空列表；返回的列表本身在写入时已经冻结成不可变，可以安全直接发布。</p>
         *
         * <p>只读，不修改状态。</p>
         */
        private List<RagContextEvidence> snapshotInvocation(String invocationId) {
            // 按调用编号取那一次的记录，取不到说明这次调用没登记过证据。
            InvocationEvidence value = invocations.get(invocationId);
            // 不存在返回空列表；存在则直接返回内部证据——它在写入时已冻结成不可变，无需再拷一份。
            return value == null ? List.of() : value.evidence;
        }
    }

    /**
     * 一次模型调用实际注入的证据：调用编号 + 该次注入的证据列表。
     *
     * <p>调用编号既是映射的键，也冗余保存在这里，这样运行级快照排序时可以直接从记录本身取编号排序，
     * 不必回头去查映射。证据列表在写入时已冻结成不可变。</p>
     *
     * <p>不可变值对象，只存在于内存，不涉及持久化。</p>
     */
    private record InvocationEvidence(String invocationId, List<RagContextEvidence> evidence) { }
}
