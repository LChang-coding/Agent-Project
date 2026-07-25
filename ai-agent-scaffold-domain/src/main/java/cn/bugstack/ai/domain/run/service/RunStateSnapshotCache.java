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
 * 运行状态极短 TTL 只读快照。
 */
final class RunStateSnapshotCache {

    /** 快照最多存活二百毫秒，仅用于削减高频只读门禁查询。 */
    static final long DEFAULT_TTL_NANOS = 200_000_000L;
    /** 单实例默认最多缓存的运行数。 */
    static final int DEFAULT_MAX_ENTRIES = 2_048;
    /** 分片锁数量，限制同键回源而不全局串行。 */
    private static final int STRIPE_COUNT = 64;
    /** 每六十四次读取触发一次维护。 */
    private static final long CLEANUP_MASK = 63L;

    /** 当前实例采用的有效期。 */
    private final long ttlNanos;
    /** 当前实例采用的容量上限。 */
    private final int maxEntries;
    /** 可注入的单调时钟。 */
    private final LongSupplier nanoTime;
    /** 按租户、用户、运行隔离的快照。 */
    private final ConcurrentHashMap<RunKey, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** 同键回源互斥锁数组。 */
    private final Object[] stripes = new Object[STRIPE_COUNT];
    /** 容量淘汰和批量清理的全局互斥锁。 */
    private final Object maintenanceMonitor = new Object();
    /** 触发摊销清理的读取计数。 */
    private final AtomicLong reads = new AtomicLong();

    /**
     * 创建默认快照容器；无参数；返回极短 TTL 有界缓存。
     */
    RunStateSnapshotCache() {
        this(DEFAULT_TTL_NANOS, DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    /**
     * 创建可测试快照容器；参数是 TTL、容量与单调时钟；返回有界缓存。
     */
    RunStateSnapshotCache(long ttlNanos, int maxEntries, LongSupplier nanoTime) {
        if (ttlNanos <= 0 || ttlNanos > DEFAULT_TTL_NANOS || maxEntries <= 0) {
            throw new IllegalArgumentException("运行状态快照参数不合法");
        }
        this.ttlNanos = ttlNanos;
        this.maxEntries = maxEntries;
        this.nanoTime = nanoTime;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
    }

    /**
     * 读取运行快照；参数是可信身份、运行ID和数据库加载器；返回不可变快照。
     */
    Snapshot get(String tenantId, String userId, String runId, Supplier<ChatRunEntity> loader) {
        RunKey key = key(tenantId, userId, runId);
        long now = nanoTime.getAsLong();
        Snapshot current = snapshots.get(key);
        if (current != null && current.expiresAtNanos() > now) {
            cleanupSometimes(now);
            return current;
        }
        Object stripe = stripe(key);
        // 同一运行只允许一个线程回源数据库，其他键仍可并发加载。
        synchronized (stripe) {
            now = nanoTime.getAsLong();
            current = snapshots.get(key);
            if (current != null && current.expiresAtNanos() > now) {
                cleanupSometimes(now);
                return current;
            }
            if (current != null) {
                snapshots.remove(key, current);
            }
            ChatRunEntity loaded = loader.get();
            long loadedAt = nanoTime.getAsLong();
            Snapshot loadedSnapshot = snapshot(loaded, loadedAt + ttlNanos);
            putBounded(key, loadedSnapshot, loadedAt);
            cleanupSometimes(loadedAt);
            return loadedSnapshot;
        }
    }

    /**
     * 失效运行快照；参数是可信身份和运行ID；无返回值。
     */
    void invalidate(String tenantId, String userId, String runId) {
        RunKey key = key(tenantId, userId, runId);
        synchronized (stripe(key)) {
            snapshots.remove(key);
        }
    }

    /** 从缓存副本再次复制实体，防止调用方修改共享快照。 */
    ChatRunEntity materialize(Snapshot snapshot) {
        return snapshot.run() == null ? null : copy(snapshot.run());
    }

    /** 返回当前缓存条目数，供容量测试验证。 */
    int size() {
        return snapshots.size();
    }

    /** 清理过期项并在容量满时淘汰最早创建的快照。 */
    private void putBounded(RunKey key, Snapshot value, long now) {
        synchronized (maintenanceMonitor) {
            removeExpired(now);
            if (!snapshots.containsKey(key)) {
                while (snapshots.size() >= maxEntries) {
                    RunKey oldest = snapshots.entrySet().stream()
                            .min(Comparator.comparingLong(entry -> entry.getValue().createdAtNanos()))
                            .map(java.util.Map.Entry::getKey).orElse(null);
                    if (oldest == null) {
                        break;
                    }
                    snapshots.remove(oldest);
                }
            }
            snapshots.put(key, value);
        }
    }

    /** 每六十四次读取做一次摊销过期清理。 */
    private void cleanupSometimes(long now) {
        if ((reads.incrementAndGet() & CLEANUP_MASK) == 0) {
            synchronized (maintenanceMonitor) {
                removeExpired(now);
            }
        }
    }

    /** 删除所有 TTL 已到期快照。 */
    private void removeExpired(long now) {
        snapshots.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    /** 按键哈希选择局部回源锁。 */
    private Object stripe(RunKey key) {
        return stripes[(key.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    /** 构造包含租户、用户和运行的隔离键。 */
    private RunKey key(String tenantId, String userId, String runId) {
        return new RunKey(tenantId == null || tenantId.isBlank() ? null : tenantId, userId, runId);
    }

    /** 仅截取状态检查所需运行字段，并记录单调时钟有效期。 */
    private Snapshot snapshot(ChatRunEntity run, long expiresAtNanos) {
        long createdAtNanos = expiresAtNanos - ttlNanos;
        return run == null
                ? new Snapshot(null, null, null, createdAtNanos, expiresAtNanos)
                : new Snapshot(copy(run), run.getStatus(), run.getCurrentContextRevision(), createdAtNanos,
                expiresAtNanos);
    }

    /** 深复制可变集合，保证缓存不会暴露仓储实体引用。 */
    private ChatRunEntity copy(ChatRunEntity run) {
        return ChatRunEntity.builder().runId(run.getRunId()).turnId(run.getTurnId()).tenantId(run.getTenantId())
                .userId(run.getUserId()).sessionId(run.getSessionId()).sourceType(run.getSourceType())
                .sourceId(run.getSourceId()).ragEnabled(run.getRagEnabled()).ragMode(run.getRagMode())
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

    /** 不可变的运行状态只读快照。 */
    record Snapshot(ChatRunEntity run, RunStatus status, Long contextRevision, long createdAtNanos,
                    long expiresAtNanos) {
    }

    /** 快照的租户隔离复合键。 */
    private record RunKey(String tenantId, String userId, String runId) {
    }
}
