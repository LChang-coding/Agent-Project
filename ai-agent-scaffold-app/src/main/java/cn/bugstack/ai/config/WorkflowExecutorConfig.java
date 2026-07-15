package cn.bugstack.ai.config;

import cn.bugstack.ai.types.observability.TraceableThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流专用有界执行器配置。
 */
@Configuration
@EnableConfigurationProperties(WorkflowExecutorProperties.class)
public class WorkflowExecutorConfig {

    /**
     * 创建工作流编排执行器；过载时显式拒绝，避免占用 HTTP 或订阅线程。
     */
    @Bean(name = "workflowCoordinatorExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "workflowCoordinatorExecutor")
    public ExecutorService workflowCoordinatorExecutor(WorkflowExecutorProperties properties) {
        WorkflowExecutorProperties.Pool pool = properties.getCoordinator();
        validate(pool, true);
        return executor(pool, new ArrayBlockingQueue<>(pool.getQueueCapacity()),
                new ThreadPoolExecutor.AbortPolicy(), "workflow-coordinator-");
    }

    /**
     * 创建 DAG 节点执行器；零容量交接和 CallerRuns 避免 coordinator 嵌套等待饥饿。
     */
    @Bean(name = "workflowNodeExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "workflowNodeExecutor")
    public ExecutorService workflowNodeExecutor(WorkflowExecutorProperties properties) {
        WorkflowExecutorProperties.Pool pool = properties.getNode();
        validate(pool, false);
        return executor(pool, new SynchronousQueue<>(), runInCallerUnlessShutdown(),
                "workflow-node-");
    }

    private RejectedExecutionHandler runInCallerUnlessShutdown() {
        return (task, executor) -> {
            if (executor.isShutdown()) {
                throw new java.util.concurrent.RejectedExecutionException("工作流节点执行器已关闭");
            }
            task.run();
        };
    }

    private ExecutorService executor(WorkflowExecutorProperties.Pool properties, BlockingQueue<Runnable> queue,
                                     RejectedExecutionHandler rejection, String threadPrefix) {
        TraceableThreadPoolExecutor executor = new TraceableThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(), properties.getKeepAliveSeconds(), TimeUnit.SECONDS, queue,
                workflowThreadFactory(threadPrefix), rejection);
        executor.allowCoreThreadTimeOut(properties.isAllowCoreThreadTimeout());
        return executor;
    }

    private ThreadFactory workflowThreadFactory(String threadPrefix) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory delegate = Executors.defaultThreadFactory();
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setName(threadPrefix + sequence.incrementAndGet());
            return thread;
        };
    }

    private void validate(WorkflowExecutorProperties.Pool properties, boolean coordinator) {
        boolean invalidQueue = coordinator ? properties.getQueueCapacity() < 1 : properties.getQueueCapacity() != 0;
        if (properties.getCorePoolSize() < 1 || properties.getMaxPoolSize() < properties.getCorePoolSize()
                || invalidQueue || properties.getKeepAliveSeconds() < 1L) {
            throw new IllegalArgumentException("工作流执行器配置不合法");
        }
    }
}
