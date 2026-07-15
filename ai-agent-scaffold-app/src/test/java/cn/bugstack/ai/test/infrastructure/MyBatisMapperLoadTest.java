package cn.bugstack.ai.test.infrastructure;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.io.Reader;

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
    }
}
