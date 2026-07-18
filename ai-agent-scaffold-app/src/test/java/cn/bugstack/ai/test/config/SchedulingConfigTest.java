package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.SchedulingConfig;
import cn.bugstack.ai.infrastructure.rag.worker.RagIngestDispatcher;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.lang.reflect.Method;

/** 定时任务基础设施边界测试。 */
public class SchedulingConfigTest {

    /** 验证调度基础设施不依赖受开关控制的 RAG Outbox bean。 */
    @Test
    public void shouldEnableSchedulingWithoutRagOutbox() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingConfig.class);
            context.refresh();

            Assert.assertTrue(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
            Assert.assertEquals(0, context.getBeanNamesForType(
                    cn.bugstack.ai.infrastructure.rag.outbox.RagOutboxPublisher.class).length);
        }
    }

    /** 验证 Worker 的 MySQL 到期任务扫描仍是定时入口。 */
    @Test
    public void shouldKeepWorkerDatabaseScanScheduled() throws Exception {
        Method scanDueTasks = RagIngestDispatcher.class.getMethod("scanDueTasks");
        Scheduled scheduled = scanDueTasks.getAnnotation(Scheduled.class);

        Assert.assertNotNull(scheduled);
        Assert.assertEquals("${ai.rag.worker.poll-delay-ms:2000}", scheduled.fixedDelayString());
    }
}
