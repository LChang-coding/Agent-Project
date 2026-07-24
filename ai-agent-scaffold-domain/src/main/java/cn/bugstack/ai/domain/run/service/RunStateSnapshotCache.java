package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 运行状态极短 TTL 只读快照。
 */
final class RunStateSnapshotCache {

    static final long DEFAULT_TTL_NANOS = 200_000_000L;
    static final int DEFAULT_MAX_ENTRIES = 2_048;
    private static final int STRIPE_COUNT = 64;
    private static final long CLEANUP_MASK = 63L;

    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<RunKey, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Object[] stripes = new Object[STRIPE_COUNT];
    private final Object maintenanceMonitor = new Object();
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

    ChatRunEntity materialize(Snapshot snapshot) {
        return snapshot.run() == null ? null : copy(snapshot.run());
    }

    int size() {
        return snapshots.size();
    }

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

    private void cleanupSometimes(long now) {
        if ((reads.incrementAndGet() & CLEANUP_MASK) == 0) {
            synchronized (maintenanceMonitor) {
                removeExpired(now);
            }
        }
    }

    private void removeExpired(long now) {
        snapshots.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    private Object stripe(RunKey key) {
        return stripes[(key.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    private RunKey key(String tenantId, String userId, String runId) {
        return new RunKey(tenantId == null || tenantId.isBlank() ? null : tenantId, userId, runId);
    }

    private Snapshot snapshot(ChatRunEntity run, long expiresAtNanos) {
        long createdAtNanos = expiresAtNanos - ttlNanos;
        return run == null
                ? new Snapshot(null, null, null, createdAtNanos, expiresAtNanos)
                : new Snapshot(copy(run), run.getStatus(), run.getCurrentContextRevision(), createdAtNanos,
                expiresAtNanos);
    }

    private ChatRunEntity copy(ChatRunEntity run) {
        return ChatRunEntity.builder().runId(run.getRunId()).turnId(run.getTurnId()).tenantId(run.getTenantId())
                .userId(run.getUserId()).sessionId(run.getSessionId()).sourceType(run.getSourceType())
                .sourceId(run.getSourceId()).ragEnabled(run.getRagEnabled()).traceId(run.getTraceId())
                .status(run.getStatus()).version(run.getVersion())
                .baseContextRevision(run.getBaseContextRevision())
                .currentContextRevision(run.getCurrentContextRevision()).predecessorRunId(run.getPredecessorRunId())
                .successorRunId(run.getSuccessorRunId()).userMessageId(run.getUserMessageId())
                .steerInstruction(run.getSteerInstruction()).terminalReason(run.getTerminalReason())
                .cancelRequestedAt(run.getCancelRequestedAt()).startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt()).build();
    }

    record Snapshot(ChatRunEntity run, RunStatus status, Long contextRevision, long createdAtNanos,
                    long expiresAtNanos) {
    }

    private record RunKey(String tenantId, String userId, String runId) {
    }
}
