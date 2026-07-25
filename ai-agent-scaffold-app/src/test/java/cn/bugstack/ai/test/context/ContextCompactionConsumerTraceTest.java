package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.context.model.ContextCompactionCommand;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.trigger.listener.ContextCompactionConsumer;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 上下文压缩 Kafka 消费链路恢复测试。
 */
public class ContextCompactionConsumerTraceTest {

    @After
    public void clearContext() {
        TenantContextHolder.clear();
        TraceContext.clear();
    }

    @Test
    public void shouldRestoreTraceAndTenantContextFromCommand() {
        ContextCompactionConsumer consumer = new ContextCompactionConsumer(
                new ObjectMapper(), null, null, new ContextPolicyProperties());
        ContextCompactionCommand command = new ContextCompactionCommand(
                "task-1", "tenant-1", "user-1", "session-1",
                1, 10, 2, "v1", "trace-compaction");

        ReflectionTestUtils.invokeMethod(consumer, "bindTenantContext", command);

        Assert.assertEquals("trace-compaction", TraceContext.getTraceId());
        Assert.assertEquals("tenant-1", TenantContextHolder.getTenantId());
        Assert.assertEquals("user-1", TenantContextHolder.getUserId());
    }
}
