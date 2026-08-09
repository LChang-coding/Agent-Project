package cn.bugstack.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作流专用执行器配置。
 */
@Data
@ConfigurationProperties(prefix = "workflow.executor", ignoreInvalidFields = true)
public class WorkflowExecutorProperties {

    private Pool coordinator = Pool.coordinatorDefaults();
    private Pool node = Pool.nodeDefaults();
    private NodeRetry nodeRetry = new NodeRetry();

    /**
     * 单个工作流执行器的线程和排队边界。
     */
    @Data
    public static class Pool {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private long keepAliveSeconds;
        private boolean allowCoreThreadTimeout;

        private static Pool coordinatorDefaults() {
            Pool value = new Pool();
            value.corePoolSize = 1;
            value.maxPoolSize = 2;
            value.queueCapacity = 8;
            value.keepAliveSeconds = 30L;
            value.allowCoreThreadTimeout = true;
            return value;
        }

        private static Pool nodeDefaults() {
            Pool value = new Pool();
            value.corePoolSize = 2;
            value.maxPoolSize = 4;
            value.queueCapacity = 0;
            value.keepAliveSeconds = 30L;
            value.allowCoreThreadTimeout = true;
            return value;
        }
    }

    /** Agent 节点发生暂时性错误时的有限重试配置。 */
    @Data
    public static class NodeRetry {
        private int maxAttempts = 3;
        private long initialBackoffMillis = 500L;
        private long maxBackoffMillis = 4_000L;
        private long cancellationPollMillis = 100L;
    }
}
