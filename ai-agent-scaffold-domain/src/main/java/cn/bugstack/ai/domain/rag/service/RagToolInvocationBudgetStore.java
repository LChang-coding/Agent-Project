package cn.bugstack.ai.domain.rag.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Concurrent run-level invocation and context-token budget for model-triggered RAG. */
@Service
public class RagToolInvocationBudgetStore {

    private static final int DEFAULT_MAX_INVOCATIONS = 3;
    private static final int DEFAULT_MAX_TOKENS = 8_000;

    private final int maxInvocations;
    private final int maxTokens;
    private final ConcurrentHashMap<Scope, Usage> usages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Scope, Instant> touchedAt = new ConcurrentHashMap<>();
    private final Clock clock;
    private static final Duration ENTRY_TTL = Duration.ofHours(1);
    private static final int MAX_ENTRIES = 10_000;

    @Autowired
    public RagToolInvocationBudgetStore() {
        this(DEFAULT_MAX_INVOCATIONS, DEFAULT_MAX_TOKENS);
    }

    public RagToolInvocationBudgetStore(int maxInvocations, int maxTokens) {
        this(maxInvocations, maxTokens, Clock.systemUTC());
    }

    RagToolInvocationBudgetStore(int maxInvocations, int maxTokens, Clock clock) {
        if (maxInvocations < 1 || maxTokens < 1) throw new IllegalArgumentException("RAG工具预算必须大于0");
        this.maxInvocations = maxInvocations;
        this.maxTokens = maxTokens;
        this.clock = clock;
    }

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

    public Usage snapshot(String tenantId, String userId, String runId) {
        cleanupExpired();
        return usages.getOrDefault(Scope.of(tenantId, userId, runId), new Usage(0, 0));
    }

    public final class Reservation {
        private final Scope scope;
        private final int reservedTokens;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(Scope scope, int reservedTokens) {
            this.scope = scope;
            this.reservedTokens = reservedTokens;
        }

        public void complete(int actualTokens) {
            if (actualTokens < 0 || actualTokens > reservedTokens) {
                throw new IllegalArgumentException("实际Token不能超过预留预算");
            }
            if (!closed.compareAndSet(false, true)) return;
            usages.computeIfPresent(scope, (key, usage) ->
                    new Usage(usage.invocations, usage.consumedTokens - reservedTokens + actualTokens));
            touchedAt.put(scope, clock.instant());
        }

        public void rollback() {
            if (!closed.compareAndSet(false, true)) return;
            usages.computeIfPresent(scope, (key, usage) -> {
                Usage rolledBack = new Usage(usage.invocations - 1, usage.consumedTokens - reservedTokens);
                return rolledBack.invocations == 0 && rolledBack.consumedTokens == 0 ? null : rolledBack;
            });
            touchedAt.put(scope, clock.instant());
        }
    }

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

    private record Scope(String tenantId, String userId, String runId) {
        private static Scope of(String tenantId, String userId, String runId) {
            requireText(tenantId, "租户ID");
            requireText(userId, "用户ID");
            requireText(runId, "运行ID");
            return new Scope(tenantId, userId, runId);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }
}
