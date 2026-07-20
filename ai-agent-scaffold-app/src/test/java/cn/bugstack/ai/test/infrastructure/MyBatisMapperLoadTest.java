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
                "mybatis/mapper/rag_document_version_mapper.xml",
                "mybatis/mapper/rag_ingest_task_mapper.xml",
                "mybatis/mapper/rag_knowledge_base_delete_task_mapper.xml",
                "mybatis/mapper/rag_retrieval_profile_mapper.xml",
                "mybatis/mapper/rag_agent_binding_mapper.xml",
                "mybatis/mapper/rag_retrieval_record_mapper.xml",
                "mybatis/mapper/rag_retrieval_citation_mapper.xml",
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
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IChatMessageDao.queryValidMessage"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IToolCallLogDao.summarizeBySessionId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao.countContextAssets"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao.updateByTenantAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.claimDue"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.queryDueCandidates"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.claimCancelledForCleanup"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.updateClaimedByTenantFenceAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.heartbeatClaimed"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.insert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.queryByTenantAndTaskId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.updateByTenantAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.queryDueCandidates"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.claimDue"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.heartbeatClaimed"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.updateClaimedByTenantFenceAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.queryByTenantAndTaskIdForUpdate"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagDocumentDao.countNotDeletedByTenantAndKnowledgeBaseId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao.countNotDeletedByTenantAndKnowledgeBaseId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagChunkDao.countAllByTenantAndKnowledgeBaseId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.countDocumentsWithoutCompletedDelete"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao.countActiveByTenantAndKnowledgeBaseId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao.markReadyByTenantAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao.queryByTenantAndProfileId"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao.updateByTenantAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao.queryActiveByTenantAndTarget"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao.softDeleteByTenantAndRevision"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagRetrievalRecordDao.insert"));
        Assert.assertTrue(configuration.hasStatement("cn.bugstack.ai.infrastructure.dao.IRagRetrievalCitationDao.insertBatch"));
        assertContextInsightAggregateScopes(configuration);
        assertScheduleReconcileScopes(configuration);
        assertRagTenantAndClaimScopes(configuration);
    }

    private void assertRagTenantAndClaimScopes(Configuration configuration) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", "tenant_1");
        parameters.put("taskId", "task_1");
        parameters.put("leaseOwner", "worker_1");
        parameters.put("now", java.time.LocalDateTime.now());
        parameters.put("leaseUntil", java.time.LocalDateTime.now().plusMinutes(1));

        String claimSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.claimDue", parameters);
        Assert.assertTrue(claimSql.contains("tenant_id = ?"));
        Assert.assertTrue(claimSql.contains("task_id = ?"));
        Assert.assertTrue(claimSql.contains("attempt_count < max_attempts"));
        Assert.assertTrue(claimSql.contains("fencing_token = fencing_token + 1"));
        Assert.assertTrue(claimSql.contains("row_version = row_version + 1"));
        Assert.assertTrue(claimSql.contains("status = 'retrying'"));
        Assert.assertTrue(claimSql.contains("status = 'running'"));
        Assert.assertTrue(claimSql.contains("lease_until <= ?"));

        parameters.put("limit", 20);
        String candidatesSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.queryDueCandidates", parameters);
        Assert.assertTrue(candidatesSql.contains("SELECT tenant_id AS tenantId, task_id AS jobId"));
        Assert.assertFalse(candidatesSql.contains("checkpoint"));
        Assert.assertTrue(candidatesSql.contains("status = 'cancel_requested'"));

        parameters.put("expectedRevision", 7L);
        parameters.put("expectedFencingToken", 11L);
        parameters.put("task", Map.of("taskId", "task_1", "stage", "indexing", "status", "completed",
                "attemptCount", 1, "maxAttempts", 3, "fencingToken", 11L));
        String workerSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.updateClaimedByTenantFenceAndRevision",
                parameters);
        Assert.assertTrue(workerSql.contains("tenant_id = ?"));
        Assert.assertTrue(workerSql.contains("task_id = ?"));
        Assert.assertTrue(workerSql.contains("row_version = ?"));
        Assert.assertTrue(workerSql.contains("lease_owner = ?"));
        Assert.assertTrue(workerSql.contains("fencing_token = ?"));
        Assert.assertTrue(workerSql.contains("lease_until > ?"));

        String heartbeatSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.heartbeatClaimed", parameters);
        Assert.assertTrue(heartbeatSql.contains("tenant_id = ?"));
        Assert.assertTrue(heartbeatSql.contains("lease_owner = ?"));
        Assert.assertTrue(heartbeatSql.contains("fencing_token = ?"));
        Assert.assertFalse(heartbeatSql.contains("row_version"));

        parameters.put("versionId", "version_1");
        String chunkQuerySql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagChunkDao.queryListByTenantAndVersionId", parameters);
        Assert.assertTrue(chunkQuerySql.contains("tenant_id = ?"));
        Assert.assertTrue(chunkQuerySql.contains("version_id = ?"));

        parameters.put("targetType", "agent");
        parameters.put("targetId", "agent_1");
        String bindingSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao.queryActiveByTenantAndTarget", parameters);
        Assert.assertTrue(bindingSql.contains("tenant_id = ?"));
        Assert.assertTrue(bindingSql.contains("target_type = ?"));
        Assert.assertTrue(bindingSql.contains("target_id = ?"));
        Assert.assertTrue(bindingSql.contains("status = 'active'"));

        parameters.put("expectedRevision", 2L);
        Map<String, Object> profile = new HashMap<>();
        profile.put("profileId", "profile_1");
        profile.put("profileName", "hybrid");
        profile.put("denseEnabled", 1);
        profile.put("sparseEnabled", 1);
        profile.put("fusionStrategy", "rrf");
        profile.put("denseWeight", 1);
        profile.put("sparseWeight", 1);
        profile.put("denseTopK", 20);
        profile.put("sparseTopK", 20);
        profile.put("fusionTopK", 20);
        profile.put("rerankEnabled", 1);
        profile.put("rerankTopK", 10);
        profile.put("finalTopK", 5);
        profile.put("neighborWindow", 1);
        profile.put("maxContextTokens", 1024);
        profile.put("queryRewriteEnabled", 0);
        profile.put("deduplicateEnabled", 1);
        profile.put("configJson", "{}");
        parameters.put("profile", profile);
        String profileUpdateSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao.updateByTenantAndRevision", parameters);
        Assert.assertTrue(profileUpdateSql.contains("tenant_id = ?"));
        Assert.assertTrue(profileUpdateSql.contains("profile_id = ?"));
        Assert.assertTrue(profileUpdateSql.contains("revision = ?"));

        parameters.put("bindingId", "binding_1");
        String bindingDeleteSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao.softDeleteByTenantAndRevision", parameters);
        Assert.assertTrue(bindingDeleteSql.contains("tenant_id = ?"));
        Assert.assertTrue(bindingDeleteSql.contains("binding_id = ?"));
        Assert.assertTrue(bindingDeleteSql.contains("revision = ?"));

        String kbDeleteQuerySql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.queryByTenantAndTaskId",
                parameters);
        Assert.assertTrue(kbDeleteQuerySql.contains("tenant_id = ?"));
        Assert.assertTrue(kbDeleteQuerySql.contains("task_id = ?"));
        Assert.assertTrue(kbDeleteQuerySql.contains("deleted = 0"));

        parameters.put("task", Map.of("taskId", "kb-delete-1", "status", "running",
                "checkpoint", "{}", "attemptCount", 1, "maxAttempts", 5,
                "fencingToken", 3L));
        String kbDeleteUpdateSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.updateByTenantAndRevision",
                parameters);
        Assert.assertTrue(kbDeleteUpdateSql.contains("tenant_id = ?"));
        Assert.assertTrue(kbDeleteUpdateSql.contains("task_id = ?"));
        Assert.assertTrue(kbDeleteUpdateSql.contains("row_version = ?"));
        Assert.assertTrue(kbDeleteUpdateSql.contains("row_version = row_version + 1"));

        String kbDeleteCandidatesSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.queryDueCandidates",
                parameters);
        Assert.assertTrue(kbDeleteCandidatesSql.contains("SELECT tenant_id AS tenantId, task_id AS taskId"));
        Assert.assertFalse(kbDeleteCandidatesSql.contains("checkpoint"));
        Assert.assertTrue(kbDeleteCandidatesSql.contains("lease_until <= ?"));
        Assert.assertTrue(kbDeleteCandidatesSql.contains("status = 'waiting'"));

        String kbDeleteClaimSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.claimDue", parameters);
        Assert.assertTrue(kbDeleteClaimSql.contains("tenant_id = ?"));
        Assert.assertTrue(kbDeleteClaimSql.contains("task_id = ?"));
        Assert.assertTrue(kbDeleteClaimSql.contains("fencing_token = fencing_token + 1"));
        Assert.assertTrue(kbDeleteClaimSql.contains("row_version = row_version + 1"));
        Assert.assertTrue(kbDeleteClaimSql.contains("CASE WHEN status = 'waiting' THEN 0 ELSE 1 END"));

        String kbDeleteHeartbeatSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.heartbeatClaimed",
                parameters);
        Assert.assertTrue(kbDeleteHeartbeatSql.contains("lease_owner = ?"));
        Assert.assertTrue(kbDeleteHeartbeatSql.contains("fencing_token = ?"));
        Assert.assertTrue(kbDeleteHeartbeatSql.contains("lease_until > ?"));

        parameters.put("knowledgeBaseId", "kb_1");
        String kbDeleteLockSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao.queryByTenantAndTaskIdForUpdate",
                parameters);
        Assert.assertTrue(kbDeleteLockSql.contains("tenant_id = ?"));
        Assert.assertTrue(kbDeleteLockSql.contains("task_id = ?"));
        Assert.assertTrue(kbDeleteLockSql.contains("FOR UPDATE"));

        String incompleteChildSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao.countDocumentsWithoutCompletedDelete",
                parameters);
        Assert.assertTrue(incompleteChildSql.contains("d.tenant_id = ?"));
        Assert.assertTrue(incompleteChildSql.contains("d.kb_id = ?"));
        Assert.assertTrue(incompleteChildSql.contains("t.operation = 'delete'"));
        Assert.assertTrue(incompleteChildSql.contains("t.status = 'completed'"));
        Assert.assertTrue(incompleteChildSql.contains("NOT EXISTS"));
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
        parameters.put("messageId", "msg_1");

        String messageSql = sql(configuration, "cn.bugstack.ai.infrastructure.dao.IChatMessageDao.queryMaxValidSequenceNo",
                parameters);
        Assert.assertTrue(messageSql.contains("MAX(sequence_no)"));
        Assert.assertTrue(messageSql.contains("user_id = ?"));
        Assert.assertTrue(messageSql.contains("session_id = ?"));
        Assert.assertTrue(messageSql.contains("validity_status = 'active'"));
        Assert.assertTrue(messageSql.contains("deleted = 0"));
        Assert.assertTrue(messageSql.contains("tenant_id = ?"));
        Assert.assertFalse(messageSql.contains("content"));

        String citationMessageSql = sql(configuration,
                "cn.bugstack.ai.infrastructure.dao.IChatMessageDao.queryValidMessage", parameters);
        Assert.assertTrue(citationMessageSql.contains("user_id = ?"));
        Assert.assertTrue(citationMessageSql.contains("session_id = ?"));
        Assert.assertTrue(citationMessageSql.contains("message_id = ?"));
        Assert.assertTrue(citationMessageSql.contains("validity_status = 'active'"));
        Assert.assertTrue(citationMessageSql.contains("tenant_id = ?"));
        Assert.assertTrue(citationMessageSql.contains("LIMIT 1"));

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
