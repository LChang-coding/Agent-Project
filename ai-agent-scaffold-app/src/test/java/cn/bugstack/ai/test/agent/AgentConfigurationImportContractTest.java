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

    @Test
    public void supervisorPromptShouldPermitOwnWorkAndRequireOneWaitAllSummary() throws IOException {
        String supervisor;
        try (var stream = getClass().getClassLoader().getResourceAsStream("agent/only-one-agent.yml")) {
            Assert.assertNotNull("agent/only-one-agent.yml must exist", stream);
            supervisor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        Assert.assertTrue(supervisor.contains("调用 MCP/Skill/RAG"));
        Assert.assertTrue(supervisor.contains("主 Agent 与全部子任务均结束"));
        Assert.assertTrue(supervisor.contains("只输出一次最终答案"));
        String normalized = supervisor.toLowerCase().replaceAll("\\s+", "");
        Assert.assertFalse(normalized.contains("java编程实战项目"));
        Assert.assertFalse(normalized.contains("百度检索"));
        Assert.assertFalse(normalized.contains("针对初学用户给出学习计划"));
    }

    @Test
    public void genericCodingTemplateShouldNotForceJavaReviewRules() throws IOException {
        String codingTemplate;
        try (var stream = getClass().getClassLoader().getResourceAsStream("agent/test-agent.yml")) {
            Assert.assertNotNull("agent/test-agent.yml must exist", stream);
            codingTemplate = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        Assert.assertTrue(codingTemplate.contains("requested language and ecosystem"));
        Assert.assertFalse(codingTemplate.contains("common Java best practices"));
    }

    private boolean hasActiveImport(String yaml, String expected) {
        return yaml.lines().map(String::trim).anyMatch(expected::equals);
    }
}
