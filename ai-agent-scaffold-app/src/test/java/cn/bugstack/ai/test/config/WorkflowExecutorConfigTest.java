package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.ThreadPoolConfig;
import cn.bugstack.ai.config.WorkflowExecutorConfig;
import cn.bugstack.ai.config.WorkflowExecutorProperties;
import cn.bugstack.ai.domain.agent.service.chat.ChatService;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作流专用执行器边界测试。
 */
public class WorkflowExecutorConfigTest {

    /** 校验低资源默认值；验证 coordinator 和 node 分别具备明确边界。 */
    @Test
    public void shouldCreateTwoBoundedExecutorsWithLowResourceDefaults() {
        WorkflowExecutorProperties properties = new WorkflowExecutorProperties();
        ThreadPoolExecutor coordinator = coordinator(properties);
        ThreadPoolExecutor node = node(properties);
        try {
            Assert.assertEquals(1, coordinator.getCorePoolSize());
            Assert.assertEquals(2, coordinator.getMaximumPoolSize());
            Assert.assertEquals(8, coordinator.getQueue().remainingCapacity());
            Assert.assertTrue(coordinator.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
            Assert.assertEquals(2, node.getCorePoolSize());
            Assert.assertEquals(4, node.getMaximumPoolSize());
            Assert.assertEquals(0, node.getQueue().remainingCapacity());
            Assert.assertTrue(coordinator.allowsCoreThreadTimeOut());
            Assert.assertTrue(node.allowsCoreThreadTimeOut());
        } finally {
            coordinator.shutdownNow();
            node.shutdownNow();
        }
    }

    /** 校验 coordinator 过载；验证饱和后明确拒绝且不占用提交线程。 */
    @Test
    public void shouldRejectOverloadedCoordinatorWithoutRunningOnSubmittingThread() throws Exception {
        WorkflowExecutorProperties properties = new WorkflowExecutorProperties();
        WorkflowExecutorProperties.Pool pool = properties.getCoordinator();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(1);
        pool.setAllowCoreThreadTimeout(false);
        ThreadPoolExecutor coordinator = coordinator(properties);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();
        try {
            coordinator.execute(() -> {
                workerStarted.countDown();
                await(releaseWorker);
            });
            Assert.assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            coordinator.execute(() -> { });
            try {
                coordinator.execute(() -> rejectedTaskRan.set(true));
                Assert.fail("饱和 coordinator 必须显式拒绝");
            } catch (RejectedExecutionException expected) {
                Assert.assertFalse(rejectedTaskRan.get());
            }
            ChatService service = chatService(coordinator);
            TestSubscriber<String> subscriber = scheduleWorkflow(service, () -> {
                rejectedTaskRan.set(true);
                return "unexpected";
            }).test();
            subscriber.assertError(RejectedExecutionException.class);
            Assert.assertFalse(rejectedTaskRan.get());
        } finally {
            releaseWorker.countDown();
            coordinator.shutdownNow();
        }
    }

    /** 校验 node 过载；验证节点满载时才由 coordinator 提交线程执行。 */
    @Test
    public void shouldRunOverloadedNodeOnCoordinatorThread() throws Exception {
        WorkflowExecutorProperties properties = new WorkflowExecutorProperties();
        WorkflowExecutorProperties.Pool pool = properties.getNode();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setAllowCoreThreadTimeout(false);
        ThreadPoolExecutor node = node(properties);
        ThreadPoolExecutor coordinator = coordinator(properties);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch coordinatorFinished = new CountDownLatch(1);
        try {
            node.execute(() -> {
                workerStarted.countDown();
                await(releaseWorker);
            });
            Assert.assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            AtomicReference<String> coordinatorThread = new AtomicReference<>();
            AtomicReference<String> executionThread = new AtomicReference<>();

            coordinator.execute(() -> {
                coordinatorThread.set(Thread.currentThread().getName());
                node.execute(() -> executionThread.set(Thread.currentThread().getName()));
                coordinatorFinished.countDown();
            });

            Assert.assertTrue(coordinatorFinished.await(2, TimeUnit.SECONDS));
            Assert.assertTrue(coordinatorThread.get().startsWith("workflow-coordinator-"));
            Assert.assertEquals(coordinatorThread.get(), executionThread.get());

            node.shutdown();
            try {
                node.execute(() -> { });
                Assert.fail("节点执行器关闭后必须显式拒绝，不能静默丢任务");
            } catch (RejectedExecutionException expected) {
                Assert.assertTrue(node.isShutdown());
            }
        } finally {
            releaseWorker.countDown();
            node.shutdownNow();
            coordinator.shutdownNow();
        }
    }

    /** 校验上下文传播；验证两个工作流线程池均传播并清理 trace 与可信租户身份。 */
    @Test
    public void shouldPropagateAndClearTraceAndTenantContextInBothExecutors() throws Exception {
        WorkflowExecutorProperties properties = new WorkflowExecutorProperties();
        ThreadPoolExecutor coordinator = coordinator(properties);
        ThreadPoolExecutor node = node(properties);
        try {
            assertContextPropagation(coordinator, "workflow-coordinator-");
            assertContextPropagation(node, "workflow-node-");
        } finally {
            TraceContext.clear();
            TenantContextHolder.clear();
            coordinator.shutdownNow();
            node.shutdownNow();
        }
    }

    /** 校验 Rx 取消；验证释放订阅会中断 coordinator 上的整体工作流任务。 */
    @Test
    public void shouldInterruptCoordinatorTaskWhenRxSubscriptionIsDisposed() throws Exception {
        ThreadPoolExecutor coordinator = coordinator(new WorkflowExecutorProperties());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        try {
            ChatService service = chatService(coordinator);
            TestSubscriber<String> subscriber = scheduleWorkflow(service, () -> {
                        started.countDown();
                        try {
                            Thread.sleep(10_000L);
                            completed.set(true);
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return "done";
                    }).test();
            Assert.assertTrue(started.await(2, TimeUnit.SECONDS));

            subscriber.cancel();

            Assert.assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            Assert.assertFalse(completed.get());
        } finally {
            coordinator.shutdownNow();
            Assert.assertTrue(coordinator.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /** 校验 Spring 生命周期；验证通用池与两个工作流池并存且关闭时全部释放。 */
    @Test
    public void shouldShutdownBothWorkflowExecutorsWhenApplicationContextCloses() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(ThreadPoolConfig.class, WorkflowExecutorConfig.class);
        context.refresh();
        ExecutorService coordinator = context.getBean("workflowCoordinatorExecutor", ExecutorService.class);
        ExecutorService node = context.getBean("workflowNodeExecutor", ExecutorService.class);
        Assert.assertNotSame(context.getBean("threadPoolExecutor"), coordinator);
        Assert.assertNotSame(coordinator, node);

        context.close();

        Assert.assertTrue(coordinator.isShutdown());
        Assert.assertTrue(node.isShutdown());
    }

    private static void assertContextPropagation(ThreadPoolExecutor executor, String threadPrefix) throws Exception {
        TraceContext.setTraceId("trace_workflow");
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant_1").userId("user_1")
                .username("tester").roleCode("admin").build());
        ContextSnapshot captured = snapshot(executor);
        Assert.assertEquals("trace_workflow", captured.traceId());
        Assert.assertEquals("tenant_1", captured.tenantId());
        Assert.assertEquals("user_1", captured.userId());
        Assert.assertEquals("admin", captured.roleCode());
        Assert.assertTrue(captured.threadName().startsWith(threadPrefix));

        TraceContext.clear();
        TenantContextHolder.clear();
        ContextSnapshot cleared = snapshot(executor);
        Assert.assertNull(cleared.traceId());
        Assert.assertNull(cleared.tenantId());
        Assert.assertNull(cleared.userId());
        Assert.assertNull(cleared.roleCode());
    }

    private static ContextSnapshot snapshot(ThreadPoolExecutor executor) throws Exception {
        return CompletableFuture.supplyAsync(() -> new ContextSnapshot(TraceContext.getTraceId(),
                TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(),
                Thread.currentThread().getName()), executor).get(2, TimeUnit.SECONDS);
    }

    private static ThreadPoolExecutor coordinator(WorkflowExecutorProperties properties) {
        return (ThreadPoolExecutor) new WorkflowExecutorConfig().workflowCoordinatorExecutor(properties);
    }

    private static ThreadPoolExecutor node(WorkflowExecutorProperties properties) {
        return (ThreadPoolExecutor) new WorkflowExecutorConfig().workflowNodeExecutor(properties);
    }

    private static ChatService chatService(ExecutorService coordinator) throws Exception {
        ChatService service = new ChatService();
        Field field = ChatService.class.getDeclaredField("workflowCoordinatorExecutor");
        field.setAccessible(true);
        field.set(service, coordinator);
        return service;
    }

    @SuppressWarnings("unchecked")
    private static <T> Flowable<T> scheduleWorkflow(ChatService service, Callable<T> action) throws Exception {
        Method method = ChatService.class.getDeclaredMethod("scheduleWorkflow", Callable.class);
        method.setAccessible(true);
        return (Flowable<T>) method.invoke(service, action);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ContextSnapshot(String traceId, String tenantId, String userId, String roleCode,
                                   String threadName) {
    }
}
