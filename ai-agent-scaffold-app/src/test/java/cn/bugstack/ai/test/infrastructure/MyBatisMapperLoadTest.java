package cn.bugstack.ai.test.infrastructure;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

public class MyBatisMapperLoadTest {

    @Test
    public void shouldLoadSplitMapperXml() throws Exception {
        SqlSessionFactory sqlSessionFactory;
        try (Reader reader = Resources.getResourceAsReader("mybatis/config/mybatis-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }

        Configuration configuration = sqlSessionFactory.getConfiguration();
        for (String mapperResource : new String[]{
                "mybatis/mapper/tenant_mapper.xml",
                "mybatis/mapper/user_account_mapper.xml",
                "mybatis/mapper/tenant_user_mapper.xml",
                "mybatis/mapper/user_secret_mapper.xml",
                "mybatis/mapper/chat_session_mapper.xml",
                "mybatis/mapper/chat_message_mapper.xml",
                "mybatis/mapper/chat_session_share_mapper.xml",
                "mybatis/mapper/chat_session_import_mapper.xml",
                "mybatis/mapper/model_usage_mapper.xml",
                "mybatis/mapper/artifact_asset_mapper.xml",
                "mybatis/mapper/rag_knowledge_base_mapper.xml",
                "mybatis/mapper/rag_document_mapper.xml",
                "mybatis/mapper/rag_chunk_mapper.xml",
                "mybatis/mapper/skill_definition_mapper.xml",
                "mybatis/mapper/skill_version_mapper.xml",
                "mybatis/mapper/mcp_server_config_mapper.xml",
                "mybatis/mapper/mcp_config_version_mapper.xml",
                "mybatis/mapper/tool_call_log_mapper.xml",
                "mybatis/mapper/agent_workflow_mapper.xml",
                "mybatis/mapper/agent_workflow_version_mapper.xml",
                "mybatis/mapper/agent_tenant_override_mapper.xml",
                "mybatis/mapper/agent_schedule_config_mapper.xml",
                "mybatis/mapper/agent_schedule_task_mapper.xml",
                "mybatis/mapper/agent_schedule_execution_mapper.xml",
                "mybatis/mapper/conversation_memory_snapshot_mapper.xml",
                "mybatis/mapper/context_compaction_task_mapper.xml"
        }) {
            new XMLMapperBuilder(
                    Resources.getResourceAsStream(mapperResource),
                    configuration,
                    mapperResource,
                    configuration.getSqlFragments()).parse();
        }

        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.ITenantDao.insert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IUserSecretDao.upsertByUserIdAndType"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IChatSessionDao.queryBySessionId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IModelUsageDao.insert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IModelUsageDao.upsert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IModelUsageDao.queryLatest"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IModelUsageDao.summarizeSession"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IModelUsageDao.summarizeRecent"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IModelUsageDao.cancelRunning"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.ISkillDefinitionDao.insert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.ISkillVersionDao.queryActiveBySkillId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IMcpServerConfigDao.insert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IMcpConfigVersionDao.queryActiveByMcpId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IToolCallLogDao.queryListBySessionId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IAgentWorkflowDao.queryByWorkflowId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IAgentWorkflowVersionDao.queryLatestPublished"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IAgentScheduleConfigDao.queryListByRunAsUserId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IAgentScheduleExecutionDao.queryByExecutionId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IAgentScheduleTaskDao.claimDue"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IAgentScheduleExecutionDao.queryOwnedByConfig"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IConversationMemorySnapshotDao.queryActive"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IContextCompactionTaskDao.insertIgnore"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IContextCompactionTaskDao.queryLatest"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IChatMessageDao.queryMaxValidSequenceNo"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IToolCallLogDao.summarizeBySessionId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao.countContextAssets"));
        assertContextInsightAggregateScopes(configuration);
        assertScheduleReconcileScopes(configuration);
    }

    private void assertScheduleReconcileScopes(Configuration configuration) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("limit", 50);
        String querySql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IAgentScheduleConfigDao.queryForReconcile", parameters);
        Assert.assertTrue(querySql.contains("config_hash IS NULL"));
        Assert.assertTrue(querySql.contains("config_hash = ''"));
        Assert.assertTrue(querySql.contains("last_reconciled_at IS NULL"));
        Assert.assertTrue(querySql.contains("update_time > last_reconciled_at"));
        Assert.assertTrue(querySql.contains("deleted = 0"));

        parameters.put("configId", "config_1");
        parameters.put("configHash", "hash_1");
        parameters.put("configVersion", 2L);
        parameters.put("reconciledAt", java.time.LocalDateTime.now());
        parameters.put("expectedUpdateTime", java.time.LocalDateTime.now().minusMinutes(1));
        String updateSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IAgentScheduleConfigDao.updateReconciled", parameters);
        Assert.assertTrue(updateSql.contains("last_reconciled_at = ?"));
        Assert.assertTrue(updateSql.contains("update_time = update_time"));
        Assert.assertTrue(updateSql.contains("update_time <=> ?"));
        Assert.assertFalse(updateSql.contains(", update_time = ?"));
    }

    private void assertContextInsightAggregateScopes(Configuration configuration) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", "tenant_1");
        parameters.put("userId", "user_1");
        parameters.put("ownerUserId", "user_1");
        parameters.put("sessionId", "session_1");
        parameters.put("fromSequenceExclusive", 1);
        parameters.put("visibleThroughSequence", 5);
        parameters.put("candidateLimit", 32);
        parameters.put("maxContentChars", 131072);

        String messageSql = sql(configuration, "cn.bugstack.ai.infrastructure.dao.IChatMessageDao.queryMaxValidSequenceNo",
                parameters);
        Assert.assertTrue(messageSql.contains("MAX(sequence_no)"));
        Assert.assertTrue(messageSql.contains("user_id = ?"));
        Assert.assertTrue(messageSql.contains("session_id = ?"));
        Assert.assertTrue(messageSql.contains("validity_status = 'active'"));
        Assert.assertTrue(messageSql.contains("deleted = 0"));
        Assert.assertTrue(messageSql.contains("tenant_id = ?"));
        Assert.assertFalse(messageSql.contains("content"));

        String toolSql = sql(configuration, "cn.bugstack.ai.infrastructure.dao.IToolCallLogDao.summarizeBySessionId",
                parameters);
        Assert.assertTrue(toolSql.contains("COUNT(*)"));
        Assert.assertTrue(toolSql.contains("COUNT(DISTINCT tool_id)"));
        Assert.assertTrue(toolSql.contains("user_id = ?"));
        Assert.assertTrue(toolSql.contains("session_id = ?"));
        Assert.assertTrue(toolSql.contains("deleted = 0"));
        Assert.assertTrue(toolSql.contains("tenant_id = ?"));
        Assert.assertFalse(toolSql.contains("input_json"));
        Assert.assertFalse(toolSql.contains("output_json"));

        String assetSql = sql(configuration, "cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao.countContextAssets",
                parameters);
        Assert.assertTrue(assetSql.contains("COUNT(*)"));
        Assert.assertTrue(assetSql.contains("a.owner_user_id = ?"));
        Assert.assertTrue(assetSql.contains("a.session_id = ?"));
        Assert.assertTrue(assetSql.contains("m.validity_status = 'active'"));
        Assert.assertTrue(assetSql.contains("m.role = 'user'"));
        Assert.assertTrue(assetSql.contains("a.deleted = 0"));
        Assert.assertTrue(assetSql.contains("m.deleted = 0"));
        Assert.assertTrue(assetSql.contains("a.tenant_id = ?"));
        Assert.assertFalse(assetSql.contains("extracted_text"));

        String attachmentSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao.queryContextAssets", parameters);
        Assert.assertTrue(attachmentSql.contains("LIMIT ?"));
        Assert.assertTrue(attachmentSql.contains("LEFT(extracted_text"));
        Assert.assertTrue(attachmentSql.contains("prior_content_chars"));
        Assert.assertTrue(attachmentSql.contains("ORDER BY sequence_no DESC, id DESC"));
        Assert.assertFalse(attachmentSql.contains("SELECT a.*"));

        parameters.put("tenantId", null);
        Assert.assertTrue(sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IChatMessageDao.queryMaxValidSequenceNo", parameters)
                .contains("tenant_id IS NULL OR tenant_id = ''"));
        Assert.assertTrue(sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IToolCallLogDao.summarizeBySessionId", parameters)
                .contains("tenant_id IS NULL OR tenant_id = ''"));
    }

    private String sql(Configuration configuration, String statementId, Map<String, Object> parameters) {
        BoundSql boundSql = configuration.getMappedStatement(statementId).getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
