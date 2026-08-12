package cn.bugstack.ai.test.agent;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Multi-Agent 的 MySQL/Kafka 交付物必须与应用协议同步。 */
public class AgentOrchestrationMiddlewareContractTest {
    @Test
    public void bootstrapContainsAllRequiredTopicsAndSafeDryRun() throws Exception {
        String script = read("docs/dev-ops/kafka/bootstrap-agent-orchestration-topics.sh");
        for (String topic : List.of("agent.subagent.task.v1", "agent.subagent.result.v1",
                "agent.subagent.cleanup.v1", "agent.parent.resume.v1", "${result_topic}-retry-0",
                "${result_topic}-dlt")) {
            Assert.assertTrue("missing topic " + topic, script.contains(topic));
        }
        Assert.assertTrue(script.contains("apply=\"${APPLY:-false}\""));
        Assert.assertTrue(script.contains("--if-not-exists"));

        String consumer = read("ai-agent-scaffold-trigger/src/main/java/cn/bugstack/ai/trigger/listener/SubagentResultCallbackConsumer.java");
        Assert.assertTrue(consumer.contains("topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE"));
    }

    @Test
    public void databaseVerificationCoversAllOrchestrationTables() throws Exception {
        String verify = read("docs/dev-ops/mysql/sql/2026-08-12-agent-orchestration-verify.sql");
        for (String table : List.of("agent_tool_permission", "agent_tool_approval_request",
                "agent_subagent_task", "agent_parent_inbox", "agent_parent_resume_request",
                "agent_orchestration_outbox")) {
            Assert.assertTrue("missing table " + table, verify.contains("'" + table + "'"));
        }
        Assert.assertTrue(verify.contains("COLUMN_NAME IS NULL"));
        Assert.assertTrue(verify.contains("INDEX_NAME IS NULL"));
    }

    private String read(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.exists(path)) path = Path.of("..").resolve(file);
        return Files.readString(path);
    }
}
