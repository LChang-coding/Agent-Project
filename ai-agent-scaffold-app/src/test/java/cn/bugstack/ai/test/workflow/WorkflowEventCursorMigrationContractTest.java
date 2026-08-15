package cn.bugstack.ai.test.workflow;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 通用事件游标迁移的前向兼容与非破坏性回滚测试。 */
public class WorkflowEventCursorMigrationContractTest {

    @Test
    public void shouldKeepSchemaBackfillAndRollbackSeparated() throws Exception {
        String schema = read("docs/dev-ops/mysql/sql/2026-08-04-workflow-event-cursor.sql");
        String backfill = read("docs/dev-ops/mysql/sql/2026-08-04-workflow-event-cursor-backfill.sql");
        String rollback = read("docs/dev-ops/mysql/sql/2026-08-04-workflow-event-cursor-rollback.sql");

        Assert.assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS workflow_run_event_cursor"));
        Assert.assertTrue(schema.contains("terminal_event_type"));
        Assert.assertTrue(schema.contains("terminal_sequence"));
        Assert.assertFalse(schema.toUpperCase().contains("INSERT INTO"));
        Assert.assertTrue(backfill.contains("FROM chat_run run"));
        Assert.assertTrue(backfill.contains("run.source_type IN ('workflow', 'agent')"));
        Assert.assertTrue(backfill.contains("MAX(event.sequence) + 1"));
        Assert.assertTrue(backfill.contains("WORKFLOW_CANCELLED"));
        Assert.assertFalse(backfill.contains("INSERT INTO intelligent_workflow_run"));
        Assert.assertTrue(rollback.contains("GREATEST(intelligent.next_sequence, cursor.next_sequence)"));
        Assert.assertFalse((schema + backfill + rollback).toUpperCase().contains("DROP TABLE"));
        Assert.assertFalse((schema + backfill + rollback).toUpperCase().contains("TRUNCATE"));
        Assert.assertFalse((schema + backfill + rollback).toUpperCase().contains("DELETE FROM"));
    }

    private String read(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            path = Path.of("..").resolve(file);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
