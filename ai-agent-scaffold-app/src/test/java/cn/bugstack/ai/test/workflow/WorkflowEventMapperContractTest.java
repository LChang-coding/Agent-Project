package cn.bugstack.ai.test.workflow;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** 工作流事件 SQL 的租户、行锁、序号和回放合同测试。 */
public class WorkflowEventMapperContractTest {

    @Test
    public void shouldAllocateSequenceFromLockedRunInsteadOfMaxEvent() throws Exception {
        String eventMapper;
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("mybatis/mapper/workflow_run_event_mapper.xml")) {
            Assert.assertNotNull(input);
            eventMapper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String cursorMapper;
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("mybatis/mapper/workflow_event_cursor_mapper.xml")) {
            Assert.assertNotNull(input);
            cursorMapper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Assert.assertTrue(cursorMapper.contains("FROM chat_run"));
        Assert.assertTrue(cursorMapper.contains("source_type = 'workflow'"));
        Assert.assertTrue(cursorMapper.contains("LIMIT 1 FOR UPDATE"));
        Assert.assertTrue(cursorMapper.contains("next_sequence = next_sequence + 1"));
        Assert.assertTrue(cursorMapper.contains("revision = #{expectedRevision}"));
        Assert.assertFalse(cursorMapper.toUpperCase().contains("SELECT MAX(SEQUENCE)"));
        Assert.assertTrue(cursorMapper.contains("tenant_id = #{tenantId} AND user_id = #{userId} AND run_id = #{runId}"));
        Assert.assertTrue(eventMapper.contains("INNER JOIN chat_run run"));
        Assert.assertTrue(eventMapper.contains("run.source_type = 'workflow'"));
        Assert.assertTrue(eventMapper.contains("run.trace_id = event.trace_id"));
        Assert.assertTrue(eventMapper.contains("ORDER BY event.sequence ASC"));
        Assert.assertTrue(eventMapper.contains("event.expires_at &gt; CURRENT_TIMESTAMP(3)"));
        Assert.assertFalse(cursorMapper.contains("INSERT INTO intelligent_workflow_run"));
    }
}
