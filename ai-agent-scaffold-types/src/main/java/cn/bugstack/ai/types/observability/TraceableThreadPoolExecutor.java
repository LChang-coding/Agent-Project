package cn.bugstack.ai.types.observability;

import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 可传播链路和租户身份的线程池。
 */
public class TraceableThreadPoolExecutor extends ThreadPoolExecutor {

    public TraceableThreadPoolExecutor(int corePoolSize,
                                       int maximumPoolSize,
                                       long keepAliveTime,
                                       TimeUnit unit,
                                       BlockingQueue<Runnable> workQueue,
                                       ThreadFactory threadFactory,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    @Override
    public void execute(Runnable command) {
        TenantContext capturedTenant = copyTenant(TenantContextHolder.get());
        super.execute(TraceContext.wrap(() -> runWithTenant(capturedTenant, command)));
    }

    private void runWithTenant(TenantContext capturedTenant, Runnable command) {
        TenantContext previousTenant = copyTenant(TenantContextHolder.get());
        try {
            TenantContextHolder.set(copyTenant(capturedTenant));
            command.run();
        } finally {
            TenantContextHolder.set(previousTenant);
        }
    }

    private TenantContext copyTenant(TenantContext source) {
        if (source == null) {
            return null;
        }
        return TenantContext.builder().tenantId(source.getTenantId()).userId(source.getUserId())
                .username(source.getUsername()).roleCode(source.getRoleCode()).build();
    }
}
