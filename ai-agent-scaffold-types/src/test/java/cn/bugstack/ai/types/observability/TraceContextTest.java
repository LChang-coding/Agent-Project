package cn.bugstack.ai.types.observability;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TraceContextTest {

    @Test
    public void shouldPropagateTraceIdToWrappedRunnableAndRestorePreviousContext() {
        TraceContext.setTraceId("request-trace");
        Runnable wrapped = TraceContext.wrap(() -> {
            Assert.assertEquals("request-trace", TraceContext.getTraceId());
            Assert.assertEquals("request-trace", MDC.get(TraceContext.TRACE_ID_MDC_KEY));
        });

        TraceContext.setTraceId("worker-old-trace");
        wrapped.run();

        Assert.assertEquals("worker-old-trace", TraceContext.getTraceId());
        TraceContext.clear();
    }

    @Test
    public void shouldPropagateTraceIdToWrappedCallable() throws Exception {
        TraceContext.setTraceId("callable-trace");
        Callable<String> wrapped = TraceContext.wrap(TraceContext::getTraceId);
        TraceContext.clear();

        Assert.assertEquals("callable-trace", wrapped.call());
        Assert.assertNull(TraceContext.getTraceId());
    }

    @Test
    public void shouldPropagateTraceIdThroughTraceableThreadPoolExecutor() throws Exception {
        TraceableThreadPoolExecutor executor = new TraceableThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                java.util.concurrent.Executors.defaultThreadFactory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        try {
            TraceContext.setTraceId("executor-trace");
            Future<String> future = executor.submit(TraceContext::getTraceId);

            Assert.assertEquals("executor-trace", future.get(3, TimeUnit.SECONDS));
        } finally {
            TraceContext.clear();
            executor.shutdownNow();
        }
    }
}
