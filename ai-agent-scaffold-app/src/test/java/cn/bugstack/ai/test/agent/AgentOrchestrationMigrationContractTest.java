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
        String verify = read("docs/dev-ops/mysql/sql/2026-08-12-agent-orchestration-verify.sql");

        Assert.assertTrue(upgrade.contains("CREATE TABLE IF NOT EXISTS `agent_subagent_task`"));
        Assert.assertTrue(upgrade.contains("`fencing_token` BIGINT UNSIGNED NOT NULL DEFAULT 0"));
        Assert.assertTrue(upgrade.contains("`lease_expires_at` DATETIME(3)"));
        Assert.assertTrue(upgrade.contains("`callback_claimed_at` DATETIME(3)"));
        Assert.assertTrue(upgrade.contains("`child_session_id` VARCHAR(128)"));
        Assert.assertTrue(upgrade.contains("COLUMN_NAME='child_session_id'"));
        Assert.assertTrue(upgrade.contains("COLUMN_NAME='result_summary'"));
        Assert.assertTrue(upgrade.contains("COLUMN_NAME='full_context'"));
        Assert.assertTrue(upgrade.contains("COLUMN_NAME='summary_truncated'"));
        Assert.assertTrue(upgrade.contains("`parent_ready` TINYINT UNSIGNED NOT NULL DEFAULT 0"));
        Assert.assertTrue(upgrade.contains("`parent_draft` MEDIUMTEXT DEFAULT NULL"));
        Assert.assertTrue(upgrade.contains("COLUMN_NAME='parent_ready'"));
        Assert.assertTrue(upgrade.contains("COLUMN_NAME='parent_draft'"));
        Assert.assertTrue(upgrade.contains("COLUMN_TYPE='tinyint unsigned'"));
        Assert.assertTrue(upgrade.contains("COLUMN_TYPE='mediumtext'"));
        Assert.assertTrue(upgrade.contains("COLUMN_TYPE='varchar(24)'"));
        Assert.assertTrue(upgrade.contains("AND `parent_ready`=0 AND `deleted`=0"));
        Assert.assertTrue(upgrade.contains("CREATE TABLE IF NOT EXISTS `agent_orchestration_outbox`"));
        Assert.assertTrue(upgrade.contains("UNIQUE KEY `uk_agent_outbox_event`"));
        Assert.assertTrue(rollback.contains("DROP TABLE IF EXISTS `agent_orchestration_outbox`"));
        Assert.assertTrue(rollback.contains("DROP TABLE IF EXISTS `agent_subagent_task`"));
        Assert.assertTrue(verify.contains("COALESCE(SUM(status IN ('PENDING','RUNNING','RETRYING')"));
        Assert.assertTrue(verify.contains("COALESCE(SUM(status IN ('WAITING','PENDING','RUNNING','RETRYING')"));
    }

    private String read(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.exists(path)) path = Path.of("..").resolve(file);
        return Files.readString(path);
    }
}
