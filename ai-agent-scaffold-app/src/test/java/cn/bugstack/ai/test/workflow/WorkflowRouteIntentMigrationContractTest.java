package cn.bugstack.ai.test.workflow;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 阶段 A 升级与回滚脚本的 MySQL 8 可重复执行合同。 */
public class WorkflowRouteIntentMigrationContractTest {

    @Test
    public void shouldGuardInvocationColumnsOnUpgradeAndRollback() throws Exception {
        String upgrade = read("docs/dev-ops/mysql/sql/2026-08-06-rag-invocation-route-intent.sql");
        String rollback = read("docs/dev-ops/mysql/sql/2026-08-06-rag-invocation-route-intent-rollback.sql");

        Assert.assertTrue(upgrade.contains("SET @schema_name = DATABASE()"));
        assertColumnGuard(upgrade, "chat_session");
        assertColumnGuard(upgrade, "chat_run");
        Assert.assertTrue(upgrade.contains("DEFAULT ''AUTO_CONTEXT''"));
        Assert.assertFalse(Pattern.compile("(?mi)^ALTER\\s+TABLE\\s+(chat_session|chat_run)\\s+ADD\\s+COLUMN").matcher(upgrade).find());

        Assert.assertTrue(rollback.contains("SET @schema_name = DATABASE()"));
        assertColumnGuard(rollback, "chat_session");
        assertColumnGuard(rollback, "chat_run");
        Assert.assertFalse(Pattern.compile("(?mi)^ALTER\\s+TABLE\\s+(chat_session|chat_run)\\s+DROP\\s+COLUMN").matcher(rollback).find());
    }

    @Test
    public void shouldCreateIntentTableAndRepairMissingNamedIndexes() throws Exception {
        String upgrade = read("docs/dev-ops/mysql/sql/2026-08-06-rag-invocation-route-intent.sql");

        Assert.assertTrue(upgrade.contains("CREATE TABLE IF NOT EXISTS workflow_route_intent"));
        Assert.assertTrue(upgrade.contains("ENGINE=InnoDB"));
        Assert.assertTrue(upgrade.contains("DEFAULT CHARSET=utf8mb4"));
        Assert.assertTrue(upgrade.contains("UNIQUE KEY uk_wri_node_execution"));
        Assert.assertTrue(upgrade.contains("UNIQUE KEY uk_wri_function_call"));
        assertIndexGuard(upgrade, "uk_wri_node_execution");
        assertIndexGuard(upgrade, "uk_wri_function_call");
        assertIndexGuard(upgrade, "idx_wri_trace");
        assertIndexGuard(upgrade, "idx_wri_run");
    }

    @Test
    public void shouldKeepRollbackSafeWhenObjectsAreAlreadyAbsent() throws Exception {
        String rollback = read("docs/dev-ops/mysql/sql/2026-08-06-rag-invocation-route-intent-rollback.sql");

        Assert.assertTrue(rollback.contains("DROP TABLE IF EXISTS workflow_route_intent"));
        Assert.assertTrue(rollback.contains("information_schema.COLUMNS"));
        Assert.assertTrue(rollback.contains("'SELECT 1'"));
    }

    private void assertColumnGuard(String sql, String tableName) {
        Assert.assertTrue(sql.contains("information_schema.COLUMNS"));
        Assert.assertTrue(sql.contains("TABLE_NAME='" + tableName + "' AND COLUMN_NAME='rag_invocation_mode'"));
        Assert.assertTrue(sql.contains("PREPARE stmt FROM @ddl"));
        Assert.assertTrue(sql.contains("EXECUTE stmt"));
        Assert.assertTrue(sql.contains("DEALLOCATE PREPARE stmt"));
    }

    private void assertIndexGuard(String sql, String indexName) {
        Assert.assertTrue(sql.contains("information_schema.STATISTICS"));
        Assert.assertTrue(sql.contains("TABLE_NAME='workflow_route_intent' AND INDEX_NAME='" + indexName + "'"));
    }

    private String read(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            path = Path.of("..").resolve(file);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
