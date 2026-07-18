package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IPlatformRepository;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PlatformRepository implements IPlatformRepository {

    private final ITenantDao tenantDao;

    private final IUserAccountDao userAccountDao;

    private final ITenantUserDao tenantUserDao;

    private final IUserSecretDao userSecretDao;

    private final IChatSessionDao chatSessionDao;

    private final IChatMessageDao chatMessageDao;

    private final IModelUsageDao modelUsageDao;

    private final IArtifactAssetDao artifactAssetDao;

    private final ISkillDefinitionDao skillDefinitionDao;

    private final IMcpServerConfigDao mcpServerConfigDao;

    private final IAgentScheduleConfigDao agentScheduleConfigDao;

    private final IAgentScheduleTaskDao agentScheduleTaskDao;

    private final IAgentScheduleExecutionDao agentScheduleExecutionDao;

    public PlatformRepository(ITenantDao tenantDao,
                              IUserAccountDao userAccountDao,
                              ITenantUserDao tenantUserDao,
                              IUserSecretDao userSecretDao,
                              IChatSessionDao chatSessionDao,
                              IChatMessageDao chatMessageDao,
                              IModelUsageDao modelUsageDao,
                              IArtifactAssetDao artifactAssetDao,
                              ISkillDefinitionDao skillDefinitionDao,
                              IMcpServerConfigDao mcpServerConfigDao,
                              IAgentScheduleConfigDao agentScheduleConfigDao,
                              IAgentScheduleTaskDao agentScheduleTaskDao,
                              IAgentScheduleExecutionDao agentScheduleExecutionDao) {
        this.tenantDao = tenantDao;
        this.userAccountDao = userAccountDao;
        this.tenantUserDao = tenantUserDao;
        this.userSecretDao = userSecretDao;
        this.chatSessionDao = chatSessionDao;
        this.chatMessageDao = chatMessageDao;
        this.modelUsageDao = modelUsageDao;
        this.artifactAssetDao = artifactAssetDao;
        this.skillDefinitionDao = skillDefinitionDao;
        this.mcpServerConfigDao = mcpServerConfigDao;
        this.agentScheduleConfigDao = agentScheduleConfigDao;
        this.agentScheduleTaskDao = agentScheduleTaskDao;
        this.agentScheduleExecutionDao = agentScheduleExecutionDao;
    }

    @Override
    public boolean available() {
        return tenantDao.queryTenantCount() >= 0;
    }

    public int insertTenant(TenantPO tenant) {
        return tenantDao.insert(tenant);
    }

    public int updateTenantById(TenantPO tenant) {
        return tenantDao.updateById(tenant);
    }

    public TenantPO queryTenantById(Long id) {
        return tenantDao.queryById(id);
    }

    public TenantPO queryTenantByTenantId(String tenantId) {
        return tenantDao.queryByTenantId(tenantId);
    }

    public List<TenantPO> queryTenantListByTenantId(String tenantId) {
        return tenantDao.queryListByTenantId(tenantId);
    }

    public int insertUserAccount(UserAccountPO userAccount) {
        return userAccountDao.insert(userAccount);
    }

    public int updateUserAccountById(UserAccountPO userAccount) {
        return userAccountDao.updateById(userAccount);
    }

    public UserAccountPO queryUserAccountById(Long id) {
        return userAccountDao.queryById(id);
    }

    public UserAccountPO queryUserAccountByUserId(String userId) {
        return userAccountDao.queryByUserId(userId);
    }

    public List<UserAccountPO> queryUserAccountListByUserId(String userId) {
        return userAccountDao.queryListByUserId(userId);
    }

    public int insertTenantUser(TenantUserPO tenantUser) {
        return tenantUserDao.insert(tenantUser);
    }

    public int updateTenantUserById(TenantUserPO tenantUser) {
        return tenantUserDao.updateById(tenantUser);
    }

    public TenantUserPO queryTenantUserById(Long id) {
        return tenantUserDao.queryById(id);
    }

    public List<TenantUserPO> queryTenantUserListByTenantId(String tenantId) {
        return tenantUserDao.queryListByTenantId(tenantId);
    }

    public List<TenantUserPO> queryTenantUserListByUserId(String userId) {
        return tenantUserDao.queryListByUserId(userId);
    }

    public int insertUserSecret(UserSecretPO userSecret) {
        return userSecretDao.insert(userSecret);
    }

    public int updateUserSecretById(UserSecretPO userSecret) {
        return userSecretDao.updateById(userSecret);
    }

    public UserSecretPO queryUserSecretById(Long id) {
        return userSecretDao.queryById(id);
    }

    public List<UserSecretPO> queryUserSecretListByTenantId(String tenantId) {
        return userSecretDao.queryListByTenantId(tenantId);
    }

    public List<UserSecretPO> queryUserSecretListByUserId(String userId) {
        return userSecretDao.queryListByUserId(userId);
    }

    public int insertChatSession(ChatSessionPO chatSession) {
        return chatSessionDao.insert(chatSession);
    }

    public int updateChatSessionById(ChatSessionPO chatSession) {
        return chatSessionDao.updateById(chatSession);
    }

    public ChatSessionPO queryChatSessionById(Long id) {
        return chatSessionDao.queryById(id);
    }

    public ChatSessionPO queryChatSessionBySessionId(String sessionId) {
        return chatSessionDao.queryBySessionId(sessionId);
    }

    public List<ChatSessionPO> queryChatSessionListByTenantId(String tenantId) {
        return chatSessionDao.queryListByTenantId(tenantId);
    }

    public List<ChatSessionPO> queryChatSessionListByUserId(String userId) {
        return chatSessionDao.queryListByUserId(userId);
    }

    public List<ChatSessionPO> queryChatSessionListBySessionId(String sessionId) {
        return chatSessionDao.queryListBySessionId(sessionId);
    }

    public int insertChatMessage(ChatMessagePO chatMessage) {
        return chatMessageDao.insert(chatMessage);
    }

    public int updateChatMessageById(ChatMessagePO chatMessage) {
        return chatMessageDao.updateById(chatMessage);
    }

    public ChatMessagePO queryChatMessageById(Long id) {
        return chatMessageDao.queryById(id);
    }

    public ChatMessagePO queryChatMessageByMessageId(String messageId) {
        return chatMessageDao.queryByMessageId(messageId);
    }

    public List<ChatMessagePO> queryChatMessageListByTenantId(String tenantId) {
        return chatMessageDao.queryListByTenantId(tenantId);
    }

    public List<ChatMessagePO> queryChatMessageListByUserId(String userId) {
        return chatMessageDao.queryListByUserId(userId);
    }

    public List<ChatMessagePO> queryChatMessageListBySessionId(String sessionId) {
        return chatMessageDao.queryListBySessionId(sessionId);
    }

    public int insertModelUsage(ModelUsagePO modelUsage) {
        return modelUsageDao.insert(modelUsage);
    }

    public int updateModelUsageById(ModelUsagePO modelUsage) {
        return modelUsageDao.updateById(modelUsage);
    }

    public ModelUsagePO queryModelUsageById(Long id) {
        return modelUsageDao.queryById(id);
    }

    public List<ModelUsagePO> queryModelUsageListByTenantId(String tenantId) {
        return modelUsageDao.queryListByTenantId(tenantId);
    }

    public List<ModelUsagePO> queryModelUsageListByUserId(String userId) {
        return modelUsageDao.queryListByUserId(userId);
    }

    public List<ModelUsagePO> queryModelUsageListBySessionId(String sessionId) {
        return modelUsageDao.queryListBySessionId(sessionId);
    }

    public int insertArtifactAsset(ArtifactAssetPO artifactAsset) {
        return artifactAssetDao.insert(artifactAsset);
    }

    public int updateArtifactAssetById(ArtifactAssetPO artifactAsset) {
        return artifactAssetDao.updateById(artifactAsset);
    }

    public ArtifactAssetPO queryArtifactAssetById(Long id) {
        return artifactAssetDao.queryById(id);
    }

    public ArtifactAssetPO queryArtifactAssetByAssetId(String assetId) {
        return artifactAssetDao.queryByAssetId(assetId);
    }

    public List<ArtifactAssetPO> queryArtifactAssetListByTenantId(String tenantId) {
        return artifactAssetDao.queryListByTenantId(tenantId);
    }

    public List<ArtifactAssetPO> queryArtifactAssetListByOwnerUserId(String ownerUserId) {
        return artifactAssetDao.queryListByOwnerUserId(ownerUserId);
    }

    public List<ArtifactAssetPO> queryArtifactAssetListBySessionId(String sessionId) {
        return artifactAssetDao.queryListBySessionId(sessionId);
    }

    public List<ArtifactAssetPO> queryArtifactAssetListByTenantIdAndVisibility(String tenantId, String visibility) {
        return artifactAssetDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    public int insertSkillDefinition(SkillDefinitionPO skillDefinition) {
        return skillDefinitionDao.insert(skillDefinition);
    }

    public int updateSkillDefinitionById(SkillDefinitionPO skillDefinition) {
        return skillDefinitionDao.updateById(skillDefinition);
    }

    public SkillDefinitionPO querySkillDefinitionById(Long id) {
        return skillDefinitionDao.queryById(id);
    }

    public SkillDefinitionPO querySkillDefinitionBySkillId(String skillId) {
        return skillDefinitionDao.queryBySkillId(skillId);
    }

    public List<SkillDefinitionPO> querySkillDefinitionListByTenantId(String tenantId) {
        return skillDefinitionDao.queryListByTenantId(tenantId);
    }

    public List<SkillDefinitionPO> querySkillDefinitionListByOwnerUserId(String ownerUserId) {
        return skillDefinitionDao.queryListByOwnerUserId(ownerUserId);
    }

    public List<SkillDefinitionPO> querySkillDefinitionListByTenantIdAndVisibility(String tenantId, String visibility) {
        return skillDefinitionDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    public int insertMcpServerConfig(McpServerConfigPO mcpServerConfig) {
        return mcpServerConfigDao.insert(mcpServerConfig);
    }

    public int updateMcpServerConfigById(McpServerConfigPO mcpServerConfig) {
        return mcpServerConfigDao.updateById(mcpServerConfig);
    }

    public McpServerConfigPO queryMcpServerConfigById(Long id) {
        return mcpServerConfigDao.queryById(id);
    }

    public McpServerConfigPO queryMcpServerConfigByMcpId(String mcpId) {
        return mcpServerConfigDao.queryByMcpId(mcpId);
    }

    public List<McpServerConfigPO> queryMcpServerConfigListByTenantId(String tenantId) {
        return mcpServerConfigDao.queryListByTenantId(tenantId);
    }

    public List<McpServerConfigPO> queryMcpServerConfigListByOwnerUserId(String ownerUserId) {
        return mcpServerConfigDao.queryListByOwnerUserId(ownerUserId);
    }

    public List<McpServerConfigPO> queryMcpServerConfigListByTenantIdAndVisibility(String tenantId, String visibility) {
        return mcpServerConfigDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    public int insertAgentScheduleConfig(AgentScheduleConfigPO agentScheduleConfig) {
        return agentScheduleConfigDao.insert(agentScheduleConfig);
    }

    public int updateAgentScheduleConfigById(AgentScheduleConfigPO agentScheduleConfig) {
        return agentScheduleConfigDao.updateById(agentScheduleConfig);
    }

    public AgentScheduleConfigPO queryAgentScheduleConfigById(Long id) {
        return agentScheduleConfigDao.queryById(id);
    }

    public AgentScheduleConfigPO queryAgentScheduleConfigByConfigId(String configId) {
        return agentScheduleConfigDao.queryByConfigId(configId);
    }

    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByTenantId(String tenantId) {
        return agentScheduleConfigDao.queryListByTenantId(tenantId);
    }

    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByOwnerUserId(String ownerUserId) {
        return agentScheduleConfigDao.queryListByOwnerUserId(ownerUserId);
    }

    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByRunAsUserId(String runAsUserId) {
        return agentScheduleConfigDao.queryListByRunAsUserId(runAsUserId);
    }

    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByConfigId(String configId) {
        return agentScheduleConfigDao.queryListByConfigId(configId);
    }

    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByTenantIdAndVisibility(String tenantId, String visibility) {
        return agentScheduleConfigDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    public int insertAgentScheduleTask(AgentScheduleTaskPO agentScheduleTask) {
        return agentScheduleTaskDao.insert(agentScheduleTask);
    }

    public int updateAgentScheduleTaskById(AgentScheduleTaskPO agentScheduleTask) {
        return agentScheduleTaskDao.updateById(agentScheduleTask);
    }

    public AgentScheduleTaskPO queryAgentScheduleTaskById(Long id) {
        return agentScheduleTaskDao.queryById(id);
    }

    public AgentScheduleTaskPO queryAgentScheduleTaskByTaskId(String taskId) {
        return agentScheduleTaskDao.queryByTaskId(taskId);
    }

    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByTenantId(String tenantId) {
        return agentScheduleTaskDao.queryListByTenantId(tenantId);
    }

    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByUserId(String userId) {
        return agentScheduleTaskDao.queryListByUserId(userId);
    }

    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByConfigId(String configId) {
        return agentScheduleTaskDao.queryListByConfigId(configId);
    }

    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByTaskId(String taskId) {
        return agentScheduleTaskDao.queryListByTaskId(taskId);
    }

    public int insertAgentScheduleExecution(AgentScheduleExecutionPO agentScheduleExecution) {
        return agentScheduleExecutionDao.insert(agentScheduleExecution);
    }

    public int updateAgentScheduleExecutionById(AgentScheduleExecutionPO agentScheduleExecution) {
        return agentScheduleExecutionDao.updateById(agentScheduleExecution);
    }

    public AgentScheduleExecutionPO queryAgentScheduleExecutionById(Long id) {
        return agentScheduleExecutionDao.queryById(id);
    }

    public AgentScheduleExecutionPO queryAgentScheduleExecutionByExecutionId(String executionId) {
        return agentScheduleExecutionDao.queryByExecutionId(executionId);
    }

    public List<AgentScheduleExecutionPO> queryAgentScheduleExecutionListByTenantId(String tenantId) {
        return agentScheduleExecutionDao.queryListByTenantId(tenantId);
    }

    public List<AgentScheduleExecutionPO> queryAgentScheduleExecutionListByUserId(String userId) {
        return agentScheduleExecutionDao.queryListByUserId(userId);
    }

    public List<AgentScheduleExecutionPO> queryAgentScheduleExecutionListByTaskId(String taskId) {
        return agentScheduleExecutionDao.queryListByTaskId(taskId);
    }
}
