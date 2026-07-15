package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.ThreadPoolConfig;
import cn.bugstack.ai.config.ThreadPoolConfigProperties;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 低资源线程池默认值测试。
 */
public class ThreadPoolConfigTest {

    @Test
    public void shouldCreateBoundedExecutorWithIdleCoreReclamation() throws Exception {
        ThreadPoolConfigProperties properties = new ThreadPoolConfigProperties();
        ThreadPoolExecutor executor = new ThreadPoolConfig().threadPoolExecutor(properties);
        try {
            Assert.assertEquals(4, executor.getCorePoolSize());
            Assert.assertEquals(8, executor.getMaximumPoolSize());
            Assert.assertEquals(256, executor.getQueue().remainingCapacity());
            Assert.assertTrue(executor.allowsCoreThreadTimeOut());
            Assert.assertTrue(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
        } finally {
            executor.shutdownNow();
        }
    }
}
