package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.types.exception.AppException;

/**
 * Agent 节点暂时失败时的重试规则。
 *
 * <p>一次节点访问最多尝试 {@code maxAttempts} 次。第 N 次失败后的等待时间按
 * {@code initialBackoffMillis * 2^(N-1)} 增长，但不会超过 {@code maxBackoffMillis}。
 * 等待会被拆成短片段，每个片段结束后重新检查运行是否已经取消。</p>
 */
public final class WorkflowNodeRetryPolicy {

    /** 实际休眠动作单独抽出，测试可以在不真实等待的情况下验证取消和等待时间。 */
    @FunctionalInterface
    public interface Waiter {
        void await(long millis) throws InterruptedException;
    }

    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final long cancellationPollMillis;
    private final Waiter waiter;

    public WorkflowNodeRetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis,
                                   long cancellationPollMillis) {
        this(maxAttempts, initialBackoffMillis, maxBackoffMillis, cancellationPollMillis, Thread::sleep);
    }

    public WorkflowNodeRetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis,
                                   long cancellationPollMillis, Waiter waiter) {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Agent 节点最大尝试次数必须在 1 到 10 之间");
        }
        if (initialBackoffMillis < 0L || maxBackoffMillis < initialBackoffMillis
                || cancellationPollMillis < 1L || waiter == null) {
            throw new IllegalArgumentException("Agent 节点重试等待配置不合法");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.cancellationPollMillis = cancellationPollMillis;
        this.waiter = waiter;
    }

    /** 旧测试直接创建运行时服务时也使用与生产默认值一致的规则。 */
    public static WorkflowNodeRetryPolicy defaults() {
        return new WorkflowNodeRetryPolicy(3, 500L, 4_000L, 100L);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 参数 1 表示第一次调用失败，下一次尝试前等待初始值。 */
    public long backoffMillis(int failedAttempt) {
        if (failedAttempt < 1 || initialBackoffMillis == 0L) return 0L;
        long value = initialBackoffMillis;
        for (int index = 1; index < failedAttempt && value < maxBackoffMillis; index++) {
            value = value > maxBackoffMillis / 2L ? maxBackoffMillis : value * 2L;
        }
        return Math.min(value, maxBackoffMillis);
    }

    /**
     * 等到下一次尝试，但不会整段时间一直休眠。每个短片段前后都执行状态检查，
     * 因此用户取消后不必等完整个等待时间才停止。
     */
    public void awaitNextAttempt(int failedAttempt, Runnable executableCheck) {
        if (executableCheck == null) throw new IllegalArgumentException("运行状态检查不能为空");
        long remaining = backoffMillis(failedAttempt);
        executableCheck.run();
        try {
            while (remaining > 0L) {
                long slice = Math.min(remaining, cancellationPollMillis);
                waiter.await(slice);
                remaining -= slice;
                executableCheck.run();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AppException("WORKFLOW_RETRY_INTERRUPTED", "等待再次调用 Agent 时线程被中断", exception);
        }
    }
}
