package cn.bugstack.ai.test.agent;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 保证 Supervisor 允许委派的静态子 Agent 在 dev/线上配置中真正被装配。 */
public class AgentConfigurationImportContractTest {

    @Test
    public void shouldImportSupervisorAndAllowedChildAgents() throws IOException {
        String applicationDev;
        try (var stream = getClass().getClassLoader().getResourceAsStream("application-dev.yml")) {
            Assert.assertNotNull("application-dev.yml must exist", stream);
            applicationDev = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        Assert.assertTrue(hasActiveImport(applicationDev, "- classpath:agent/only-one-agent.yml"));
        Assert.assertTrue(hasActiveImport(applicationDev, "- classpath:agent/test-agent.yml"));
        Assert.assertTrue(hasActiveImport(applicationDev, "- classpath:agent/parallel_research_app.yml"));
        Assert.assertTrue(hasActiveImport(applicationDev, "- classpath:agent/review-agent.yml"));
        Assert.assertTrue(hasActiveImport(applicationDev,
                "- optional:nacos:ai-agent-templates-dev.yml?group=DEFAULT_GROUP&refreshEnabled=false"));
    }

    private boolean hasActiveImport(String yaml, String expected) {
        return yaml.lines().map(String::trim).anyMatch(expected::equals);
    }
}
