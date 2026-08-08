package cn.bugstack.ai.domain.rag.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 模型主动检索的运行级调用次数与上下文 Token 预算存储。
 * <p>预算以租户、用户和运行为隔离键，通过 ConcurrentHashMap 的原子计算避免并发调用超额。
 * 存储仅在当前进程内生效，过期与容量清理只用于限制内存占用。</p>
 */
@Service
public class RagToolInvocationBudgetStore {

    /** 单次运行默认允许的最大 RAG 工具调用次数。 */
    private static final int DEFAULT_MAX_INVOCATIONS = 3;
    /** 单次运行默认允许的累计上下文 Token 数。 */
    private static final int DEFAULT_MAX_TOKENS = 8_000;

    /** 单次运行允许的最大 RAG 工具调用次数。 */
    private final int maxInvocations;
    /** 单次运行允许的累计上下文 Token 数。 */
    private final int maxTokens;
    /** 按运行隔离键保存的本机预算用量。 */
    private final ConcurrentHashMap<Scope, Usage> usages = new ConcurrentHashMap<>();
    /** 各预算用量最近一次读写时刻。 */
    private final ConcurrentHashMap<Scope, Instant> touchedAt = new ConcurrentHashMap<>();
    /** 用于过期判断与确定性测试的时钟。 */
    private final Clock clock;
    /** 本机预算用量无访问时的保留时长。 */
    private static final Duration ENTRY_TTL = Duration.ofHours(1);
    /** 本机预算快照的最大条目数。 */
    private static final int MAX_ENTRIES = 10_000;

    @Autowired
    /** 使用单次运行最多 3 次调用、累计 8000 Token 的默认预算。 */
    public RagToolInvocationBudgetStore() {
        this(DEFAULT_MAX_INVOCATIONS, DEFAULT_MAX_TOKENS);
    }

    /**
     * 使用指定的运行级限额创建预算存储。
     * @param maxInvocations 单次运行允许的最大 RAG 工具调用次数
     * @param maxTokens 单次运行允许预留的累计上下文 Token 数
     */
    public RagToolInvocationBudgetStore(int maxInvocations, int maxTokens) {
        this(maxInvocations, maxTokens, Clock.systemUTC());
    }

    /** 注入可控时钟，使过期清理可以被确定性测试。 */
    RagToolInvocationBudgetStore(int maxInvocations, int maxTokens, Clock clock) {
        if (maxInvocations < 1 || maxTokens < 1) throw new IllegalArgumentException("RAG工具预算必须大于0");
        this.maxInvocations = maxInvocations;
        this.maxTokens = maxTokens;
        this.clock = clock;
    }

    /**
     * 在发起检索前原子预留一次调用和上下文 Token 预算。
     *
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 运行标识
     * @param requestedTokens 本次检索要预留的最大 Token 数
     * @return 用于按实际用量结算或在失败时回滚的预留记录
     * @throws BudgetExceededException 调用次数或累计 Token 会超过运行级上限时抛出
     */
    public Reservation reserve(String tenantId, String userId, String runId, int requestedTokens) {
        Scope scope = Scope.of(tenantId, userId, runId);
        cleanupExpired();
        if (requestedTokens < 1 || requestedTokens > maxTokens) {
            throw new BudgetExceededException("请求Token预算超过单Run上限");
        }
        usages.compute(scope, (key, current) -> {
            Usage usage = current == null ? new Usage(0, 0) : current;
            if (usage.invocations >= maxInvocations || usage.consumedTokens + requestedTokens > maxTokens) {
                throw new BudgetExceededException("单Run最多调用" + maxInvocations + "次且总Token不超过" + maxTokens);
            }
            return new Usage(usage.invocations + 1, usage.consumedTokens + requestedTokens);
        });
        touchedAt.put(scope, clock.instant());
        return new Reservation(scope, requestedTokens);
    }

    /**
     * 查询当前运行已占用的预算快照。
     * @param tenantId 运行所属租户
     * @param userId 运行所属用户
     * @param runId 运行标识
     * @return 已计入的调用次数和 Token 数
     */
    public Usage snapshot(String tenantId, String userId, String runId) {
        cleanupExpired();
        return usages.getOrDefault(Scope.of(tenantId, userId, runId), new Usage(0, 0));
    }

    /** 一次预算预留，只允许成功结算或失败回滚一次。 */
    public final class Reservation {
        /** 本次预留对应的运行隔离键。 */
        private final Scope scope;
        /** 本次检索已预留的 Token 数。 */
        private final int reservedTokens;
        /** 保证成功结算和失败回滚只生效一次。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 保存本次预留范围和 Token 数，关闭时归还未实际消耗的预算。 */
        private Reservation(Scope scope, int reservedTokens) {
            this.scope = scope;
            this.reservedTokens = reservedTokens;
        }

        /**
         * 按检索结果实际 Token 数结算，释放未使用的预留额度。
         * @param actualTokens 检索结果实际占用的 Token 数
         */
        public void complete(int actualTokens) {
            if (actualTokens < 0 || actualTokens > reservedTokens) {
                throw new IllegalArgumentException("实际Token不能超过预留预算");
            }
            if (!closed.compareAndSet(false, true)) return;
            usages.computeIfPresent(scope, (key, usage) ->
                    new Usage(usage.invocations, usage.consumedTokens - reservedTokens + actualTokens));
            touchedAt.put(scope, clock.instant());
        }

        /** 检索未产生可用结果时，回滚本次调用次数和全部预留 Token。 */
        public void rollback() {
            if (!closed.compareAndSet(false, true)) return;
            usages.computeIfPresent(scope, (key, usage) -> {
                Usage rolledBack = new Usage(usage.invocations - 1, usage.consumedTokens - reservedTokens);
                return rolledBack.invocations == 0 && rolledBack.consumedTokens == 0 ? null : rolledBack;
            });
            touchedAt.put(scope, clock.instant());
        }
    }

    /**
     * 一次运行当前的 RAG 工具预算用量。
     * @param invocations 已计入的调用次数
     * @param consumedTokens 已预留或结算的 Token 数
     */
    public record Usage(int invocations, int consumedTokens) {
    }

    /** 清理过期或超量的本机预算快照，避免长时间运行导致无界内存增长。 */
    private void cleanupExpired() {
        Instant cutoff = clock.instant().minus(ENTRY_TTL);
        touchedAt.forEach((scope, touched) -> {
            if (touched.isBefore(cutoff)) {
                touchedAt.remove(scope, touched);
                usages.remove(scope);
            }
        });
        if (touchedAt.size() > MAX_ENTRIES) {
            touchedAt.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .limit(touchedAt.size() - MAX_ENTRIES).forEach(entry -> {
                        touchedAt.remove(entry.getKey(), entry.getValue());
                        usages.remove(entry.getKey());
                    });
        }
    }

    /** 租户、用户与运行共同构成的本机预算隔离键。 */
    private record Scope(String tenantId, String userId, String runId) {
        /** 校验三个可信身份字段后创建预算隔离键。 */
        private static Scope of(String tenantId, String userId, String runId) {
            requireText(tenantId, "租户ID");
            requireText(userId, "用户ID");
            requireText(runId, "运行ID");
            return new Scope(tenantId, userId, runId);
        }
    }

    /** 校验预算隔离键必需的文本字段。 */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    /** 运行级 RAG 工具调用次数或 Token 预算超限异常。 */
    public static final class BudgetExceededException extends RuntimeException {
        /**
         * 创建预算超限异常。
         * @param message 可返回给工具调用方的限额说明
         */
        public BudgetExceededException(String message) {
            super(message);
        }
    }
}
