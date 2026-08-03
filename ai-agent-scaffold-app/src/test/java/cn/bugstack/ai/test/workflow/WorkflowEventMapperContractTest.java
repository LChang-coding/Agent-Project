package cn.bugstack.ai.test.workflow;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** 工作流事件 SQL 的租户、行锁、序号和回放合同测试。 */
public class WorkflowEventMapperContractTest {

    @Test
    public void shouldAllocateSequenceFromLockedRunInsteadOfMaxEvent() throws Exception {
        String mapper;
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("mybatis/mapper/workflow_run_event_mapper.xml")) {
            Assert.assertNotNull(input);
            mapper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Assert.assertTrue(mapper.contains("LIMIT 1 FOR UPDATE"));
        Assert.assertTrue(mapper.contains("next_sequence = next_sequence + 1"));
        Assert.assertTrue(mapper.contains("revision = #{expectedRevision}"));
        Assert.assertFalse(mapper.toUpperCase().contains("SELECT MAX(SEQUENCE)"));
        Assert.assertTrue(mapper.contains("tenant_id = #{tenantId} AND user_id = #{userId} AND run_id = #{runId}"));
        Assert.assertTrue(mapper.contains("ORDER BY sequence ASC"));
        Assert.assertTrue(mapper.contains("expires_at &gt; CURRENT_TIMESTAMP(3)"));
    }
}
