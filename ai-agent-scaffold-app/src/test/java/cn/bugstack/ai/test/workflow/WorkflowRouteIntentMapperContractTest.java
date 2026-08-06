package cn.bugstack.ai.test.workflow;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** 路由意图 SQL 的幂等、作用域和条件消费合同。 */
public class WorkflowRouteIntentMapperContractTest {

    @Test
    public void shouldClaimReadAndConsumeWithinTrustedNodeScope() throws Exception {
        String mapper;
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("mybatis/mapper/workflow_route_intent_mapper.xml")) {
            Assert.assertNotNull(input);
            mapper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Assert.assertTrue(mapper.contains("INSERT IGNORE INTO workflow_route_intent"));
        Assert.assertTrue(mapper.contains("tenant_id = #{tenantId}"));
        Assert.assertTrue(mapper.contains("run_id = #{runId}"));
        Assert.assertTrue(mapper.contains("node_execution_id = #{nodeExecutionId}"));
        Assert.assertTrue(mapper.contains("function_call_id = #{functionCallId}"));
        Assert.assertTrue(mapper.contains("status = 'PENDING'"));
        Assert.assertTrue(mapper.contains("status = 'CONSUMED'"));
        Assert.assertTrue(mapper.contains("consumed_at = #{consumedAt}"));
    }
}
