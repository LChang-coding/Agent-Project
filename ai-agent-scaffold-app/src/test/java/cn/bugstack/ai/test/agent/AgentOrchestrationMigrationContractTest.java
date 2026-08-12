package cn.bugstack.ai.test.agent;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Agent 编排升级与回滚 SQL 契约测试，不连接数据库。 */
public class AgentOrchestrationMigrationContractTest {
    @Test
    public void migrationContainsLeaseFenceOutboxAndRollback() throws Exception {
        String upgrade = read("docs/dev-ops/mysql/sql/2026-08-12-agent-orchestration.sql");
        String rollback = read("docs/dev-ops/mysql/sql/2026-08-12-agent-orchestration-rollback.sql");

        Assert.assertTrue(upgrade.contains("CREATE TABLE IF NOT EXISTS `agent_subagent_task`"));
        Assert.assertTrue(upgrade.contains("`fencing_token` BIGINT UNSIGNED NOT NULL DEFAULT 0"));
        Assert.assertTrue(upgrade.contains("`lease_expires_at` DATETIME(3)"));
        Assert.assertTrue(upgrade.contains("`callback_claimed_at` DATETIME(3)"));
        Assert.assertTrue(upgrade.contains("CREATE TABLE IF NOT EXISTS `agent_orchestration_outbox`"));
        Assert.assertTrue(upgrade.contains("UNIQUE KEY `uk_agent_outbox_event`"));
        Assert.assertTrue(rollback.contains("DROP TABLE IF EXISTS `agent_orchestration_outbox`"));
        Assert.assertTrue(rollback.contains("DROP TABLE IF EXISTS `agent_subagent_task`"));
    }

    private String read(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.exists(path)) path = Path.of("..").resolve(file);
        return Files.readString(path);
    }
}
