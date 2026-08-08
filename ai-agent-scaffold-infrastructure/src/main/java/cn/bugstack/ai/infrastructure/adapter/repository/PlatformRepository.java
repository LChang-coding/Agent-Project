package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IPlatformRepository;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 平台基础表的薄适配器；不在此层补授权、事务或领域规则。 */
@Repository
public class PlatformRepository implements IPlatformRepository {

    /** 读写租户主表。 */
    private final ITenantDao tenantDao;
    /** 读写用户账号主表。 */
    private final IUserAccountDao userAccountDao;
    /** 读写租户和用户的成员关系。 */
    private final ITenantUserDao tenantUserDao;
    /** 读写密码、刷新令牌等凭证摘要。 */
    private final IUserSecretDao userSecretDao;
    /** 读写聊天会话主表。 */
    private final IChatSessionDao chatSessionDao;
    /** 读写会话消息。 */
    private final IChatMessageDao chatMessageDao;
    /** 读写模型调用用量。 */
    private final IModelUsageDao modelUsageDao;
    /** 读写用户上传资产元数据。 */
    private final IArtifactAssetDao artifactAssetDao;
    /** 读写 Skill 定义。 */
    private final ISkillDefinitionDao skillDefinitionDao;
    /** 读写 MCP 服务配置。 */
    private final IMcpServerConfigDao mcpServerConfigDao;
    /** 读写 Agent 定时配置。 */
    private final IAgentScheduleConfigDao agentScheduleConfigDao;
    /** 读写调度器生成的待执行任务。 */
    private final IAgentScheduleTaskDao agentScheduleTaskDao;
    /** 读写调度任务执行记录。 */
    private final IAgentScheduleExecutionDao agentScheduleExecutionDao;

    /** 注入平台基础表的全部 MyBatis DAO。 */
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

    /** 通过最小只读查询判断平台数据库是否可访问。 */
    @Override
    public boolean available() {
        return tenantDao.queryTenantCount() >= 0;
    }

    /** 新增租户记录。 */
    public int insertTenant(TenantPO tenant) {
        return tenantDao.insert(tenant);
    }

    /** 按数据库主键更新租户记录。 */
    public int updateTenantById(TenantPO tenant) {
        return tenantDao.updateById(tenant);
    }

    /** 按数据库主键查询租户。 */
    public TenantPO queryTenantById(Long id) {
        return tenantDao.queryById(id);
    }

    /** 按业务租户标识查询租户。 */
    public TenantPO queryTenantByTenantId(String tenantId) {
        return tenantDao.queryByTenantId(tenantId);
    }

    /** 查询指定业务租户标识对应的租户记录列表。 */
    public List<TenantPO> queryTenantListByTenantId(String tenantId) {
        return tenantDao.queryListByTenantId(tenantId);
    }

    /** 新增用户账号。 */
    public int insertUserAccount(UserAccountPO userAccount) {
        return userAccountDao.insert(userAccount);
    }

    /** 按数据库主键更新用户账号。 */
    public int updateUserAccountById(UserAccountPO userAccount) {
        return userAccountDao.updateById(userAccount);
    }

    /** 按数据库主键查询用户账号。 */
    public UserAccountPO queryUserAccountById(Long id) {
        return userAccountDao.queryById(id);
    }

    /** 按业务用户标识查询账号。 */
    public UserAccountPO queryUserAccountByUserId(String userId) {
        return userAccountDao.queryByUserId(userId);
    }

    /** 查询指定业务用户标识对应的账号记录列表。 */
    public List<UserAccountPO> queryUserAccountListByUserId(String userId) {
        return userAccountDao.queryListByUserId(userId);
    }

    /** 新增租户成员关系。 */
    public int insertTenantUser(TenantUserPO tenantUser) {
        return tenantUserDao.insert(tenantUser);
    }

    /** 按数据库主键更新租户成员关系。 */
    public int updateTenantUserById(TenantUserPO tenantUser) {
        return tenantUserDao.updateById(tenantUser);
    }

    /** 按数据库主键查询租户成员关系。 */
    public TenantUserPO queryTenantUserById(Long id) {
        return tenantUserDao.queryById(id);
    }

    /** 查询租户的全部成员关系。 */
    public List<TenantUserPO> queryTenantUserListByTenantId(String tenantId) {
        return tenantUserDao.queryListByTenantId(tenantId);
    }

    /** 查询用户所属的全部租户关系。 */
    public List<TenantUserPO> queryTenantUserListByUserId(String userId) {
        return tenantUserDao.queryListByUserId(userId);
    }

    /** 新增用户凭证摘要。 */
    public int insertUserSecret(UserSecretPO userSecret) {
        return userSecretDao.insert(userSecret);
    }

    /** 按数据库主键更新用户凭证摘要。 */
    public int updateUserSecretById(UserSecretPO userSecret) {
        return userSecretDao.updateById(userSecret);
    }

    /** 按数据库主键查询用户凭证摘要。 */
    public UserSecretPO queryUserSecretById(Long id) {
        return userSecretDao.queryById(id);
    }

    /** 查询租户范围内的用户凭证摘要。 */
    public List<UserSecretPO> queryUserSecretListByTenantId(String tenantId) {
        return userSecretDao.queryListByTenantId(tenantId);
    }

    /** 查询指定用户的凭证摘要。 */
    public List<UserSecretPO> queryUserSecretListByUserId(String userId) {
        return userSecretDao.queryListByUserId(userId);
    }

    /** 新增聊天会话。 */
    public int insertChatSession(ChatSessionPO chatSession) {
        return chatSessionDao.insert(chatSession);
    }

    /** 按数据库主键更新聊天会话。 */
    public int updateChatSessionById(ChatSessionPO chatSession) {
        return chatSessionDao.updateById(chatSession);
    }

    /** 按数据库主键查询聊天会话。 */
    public ChatSessionPO queryChatSessionById(Long id) {
        return chatSessionDao.queryById(id);
    }

    /** 按业务会话标识查询聊天会话。 */
    public ChatSessionPO queryChatSessionBySessionId(String sessionId) {
        return chatSessionDao.queryBySessionId(sessionId);
    }

    /** 查询租户范围内的聊天会话。 */
    public List<ChatSessionPO> queryChatSessionListByTenantId(String tenantId) {
        return chatSessionDao.queryListByTenantId(tenantId);
    }

    /** 查询指定用户的聊天会话。 */
    public List<ChatSessionPO> queryChatSessionListByUserId(String userId) {
        return chatSessionDao.queryListByUserId(userId);
    }

    /** 查询指定业务会话标识对应的会话记录。 */
    public List<ChatSessionPO> queryChatSessionListBySessionId(String sessionId) {
        return chatSessionDao.queryListBySessionId(sessionId);
    }

    /** 新增会话消息。 */
    public int insertChatMessage(ChatMessagePO chatMessage) {
        return chatMessageDao.insert(chatMessage);
    }

    /** 按数据库主键更新会话消息。 */
    public int updateChatMessageById(ChatMessagePO chatMessage) {
        return chatMessageDao.updateById(chatMessage);
    }

    /** 按数据库主键查询会话消息。 */
    public ChatMessagePO queryChatMessageById(Long id) {
        return chatMessageDao.queryById(id);
    }

    /** 按业务消息标识查询会话消息。 */
    public ChatMessagePO queryChatMessageByMessageId(String messageId) {
        return chatMessageDao.queryByMessageId(messageId);
    }

    /** 查询租户范围内的会话消息。 */
    public List<ChatMessagePO> queryChatMessageListByTenantId(String tenantId) {
        return chatMessageDao.queryListByTenantId(tenantId);
    }

    /** 查询指定用户的会话消息。 */
    public List<ChatMessagePO> queryChatMessageListByUserId(String userId) {
        return chatMessageDao.queryListByUserId(userId);
    }

    /** 查询指定会话的全部消息。 */
    public List<ChatMessagePO> queryChatMessageListBySessionId(String sessionId) {
        return chatMessageDao.queryListBySessionId(sessionId);
    }

    /** 新增模型调用用量记录。 */
    public int insertModelUsage(ModelUsagePO modelUsage) {
        return modelUsageDao.insert(modelUsage);
    }

    /** 按数据库主键更新模型调用用量。 */
    public int updateModelUsageById(ModelUsagePO modelUsage) {
        return modelUsageDao.updateById(modelUsage);
    }

    /** 按数据库主键查询模型调用用量。 */
    public ModelUsagePO queryModelUsageById(Long id) {
        return modelUsageDao.queryById(id);
    }

    /** 查询租户范围内的模型调用用量。 */
    public List<ModelUsagePO> queryModelUsageListByTenantId(String tenantId) {
        return modelUsageDao.queryListByTenantId(tenantId);
    }

    /** 查询指定用户的模型调用用量。 */
    public List<ModelUsagePO> queryModelUsageListByUserId(String userId) {
        return modelUsageDao.queryListByUserId(userId);
    }

    /** 查询指定会话的模型调用用量。 */
    public List<ModelUsagePO> queryModelUsageListBySessionId(String sessionId) {
        return modelUsageDao.queryListBySessionId(sessionId);
    }

    /** 新增资产元数据。 */
    public int insertArtifactAsset(ArtifactAssetPO artifactAsset) {
        return artifactAssetDao.insert(artifactAsset);
    }

    /** 按数据库主键更新资产元数据。 */
    public int updateArtifactAssetById(ArtifactAssetPO artifactAsset) {
        return artifactAssetDao.updateById(artifactAsset);
    }

    /** 按数据库主键查询资产元数据。 */
    public ArtifactAssetPO queryArtifactAssetById(Long id) {
        return artifactAssetDao.queryById(id);
    }

    /** 按业务资产标识查询资产元数据。 */
    public ArtifactAssetPO queryArtifactAssetByAssetId(String assetId) {
        return artifactAssetDao.queryByAssetId(assetId);
    }

    /** 查询租户范围内的资产元数据。 */
    public List<ArtifactAssetPO> queryArtifactAssetListByTenantId(String tenantId) {
        return artifactAssetDao.queryListByTenantId(tenantId);
    }

    /** 查询指定拥有者的资产元数据。 */
    public List<ArtifactAssetPO> queryArtifactAssetListByOwnerUserId(String ownerUserId) {
        return artifactAssetDao.queryListByOwnerUserId(ownerUserId);
    }

    /** 查询绑定到指定会话的资产元数据。 */
    public List<ArtifactAssetPO> queryArtifactAssetListBySessionId(String sessionId) {
        return artifactAssetDao.queryListBySessionId(sessionId);
    }

    /** 查询租户范围内指定可见性的资产。 */
    public List<ArtifactAssetPO> queryArtifactAssetListByTenantIdAndVisibility(String tenantId, String visibility) {
        return artifactAssetDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    /** 新增 Skill 定义。 */
    public int insertSkillDefinition(SkillDefinitionPO skillDefinition) {
        return skillDefinitionDao.insert(skillDefinition);
    }

    /** 按数据库主键更新 Skill 定义。 */
    public int updateSkillDefinitionById(SkillDefinitionPO skillDefinition) {
        return skillDefinitionDao.updateById(skillDefinition);
    }

    /** 按数据库主键查询 Skill 定义。 */
    public SkillDefinitionPO querySkillDefinitionById(Long id) {
        return skillDefinitionDao.queryById(id);
    }

    /** 按业务 Skill 标识查询定义。 */
    public SkillDefinitionPO querySkillDefinitionBySkillId(String skillId) {
        return skillDefinitionDao.queryBySkillId(skillId);
    }

    /** 查询租户范围内的 Skill 定义。 */
    public List<SkillDefinitionPO> querySkillDefinitionListByTenantId(String tenantId) {
        return skillDefinitionDao.queryListByTenantId(tenantId);
    }

    /** 查询指定拥有者创建的 Skill 定义。 */
    public List<SkillDefinitionPO> querySkillDefinitionListByOwnerUserId(String ownerUserId) {
        return skillDefinitionDao.queryListByOwnerUserId(ownerUserId);
    }

    /** 查询租户范围内指定可见性的 Skill 定义。 */
    public List<SkillDefinitionPO> querySkillDefinitionListByTenantIdAndVisibility(String tenantId, String visibility) {
        return skillDefinitionDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    /** 新增 MCP 服务配置。 */
    public int insertMcpServerConfig(McpServerConfigPO mcpServerConfig) {
        return mcpServerConfigDao.insert(mcpServerConfig);
    }

    /** 按数据库主键更新 MCP 服务配置。 */
    public int updateMcpServerConfigById(McpServerConfigPO mcpServerConfig) {
        return mcpServerConfigDao.updateById(mcpServerConfig);
    }

    /** 按数据库主键查询 MCP 服务配置。 */
    public McpServerConfigPO queryMcpServerConfigById(Long id) {
        return mcpServerConfigDao.queryById(id);
    }

    /** 按业务 MCP 标识查询服务配置。 */
    public McpServerConfigPO queryMcpServerConfigByMcpId(String mcpId) {
        return mcpServerConfigDao.queryByMcpId(mcpId);
    }

    /** 查询租户范围内的 MCP 服务配置。 */
    public List<McpServerConfigPO> queryMcpServerConfigListByTenantId(String tenantId) {
        return mcpServerConfigDao.queryListByTenantId(tenantId);
    }

    /** 查询指定拥有者创建的 MCP 服务配置。 */
    public List<McpServerConfigPO> queryMcpServerConfigListByOwnerUserId(String ownerUserId) {
        return mcpServerConfigDao.queryListByOwnerUserId(ownerUserId);
    }

    /** 查询租户范围内指定可见性的 MCP 服务配置。 */
    public List<McpServerConfigPO> queryMcpServerConfigListByTenantIdAndVisibility(String tenantId, String visibility) {
        return mcpServerConfigDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    /** 新增 Agent 定时配置。 */
    public int insertAgentScheduleConfig(AgentScheduleConfigPO agentScheduleConfig) {
        return agentScheduleConfigDao.insert(agentScheduleConfig);
    }

    /** 按数据库主键更新 Agent 定时配置。 */
    public int updateAgentScheduleConfigById(AgentScheduleConfigPO agentScheduleConfig) {
        return agentScheduleConfigDao.updateById(agentScheduleConfig);
    }

    /** 按数据库主键查询 Agent 定时配置。 */
    public AgentScheduleConfigPO queryAgentScheduleConfigById(Long id) {
        return agentScheduleConfigDao.queryById(id);
    }

    /** 按业务配置标识查询 Agent 定时配置。 */
    public AgentScheduleConfigPO queryAgentScheduleConfigByConfigId(String configId) {
        return agentScheduleConfigDao.queryByConfigId(configId);
    }

    /** 查询租户范围内的 Agent 定时配置。 */
    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByTenantId(String tenantId) {
        return agentScheduleConfigDao.queryListByTenantId(tenantId);
    }

    /** 查询指定拥有者创建的 Agent 定时配置。 */
    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByOwnerUserId(String ownerUserId) {
        return agentScheduleConfigDao.queryListByOwnerUserId(ownerUserId);
    }

    /** 查询以指定用户身份执行的 Agent 定时配置。 */
    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByRunAsUserId(String runAsUserId) {
        return agentScheduleConfigDao.queryListByRunAsUserId(runAsUserId);
    }

    /** 查询指定业务配置标识对应的 Agent 定时配置。 */
    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByConfigId(String configId) {
        return agentScheduleConfigDao.queryListByConfigId(configId);
    }

    /** 查询租户范围内指定可见性的 Agent 定时配置。 */
    public List<AgentScheduleConfigPO> queryAgentScheduleConfigListByTenantIdAndVisibility(String tenantId, String visibility) {
        return agentScheduleConfigDao.queryListByTenantIdAndVisibility(tenantId, visibility);
    }

    /** 新增调度运行时任务。 */
    public int insertAgentScheduleTask(AgentScheduleTaskPO agentScheduleTask) {
        return agentScheduleTaskDao.insert(agentScheduleTask);
    }

    /** 按数据库主键更新调度运行时任务。 */
    public int updateAgentScheduleTaskById(AgentScheduleTaskPO agentScheduleTask) {
        return agentScheduleTaskDao.updateById(agentScheduleTask);
    }

    /** 按数据库主键查询调度运行时任务。 */
    public AgentScheduleTaskPO queryAgentScheduleTaskById(Long id) {
        return agentScheduleTaskDao.queryById(id);
    }

    /** 按业务任务标识查询调度运行时任务。 */
    public AgentScheduleTaskPO queryAgentScheduleTaskByTaskId(String taskId) {
        return agentScheduleTaskDao.queryByTaskId(taskId);
    }

    /** 查询租户范围内的调度运行时任务。 */
    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByTenantId(String tenantId) {
        return agentScheduleTaskDao.queryListByTenantId(tenantId);
    }

    /** 查询指定用户的调度运行时任务。 */
    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByUserId(String userId) {
        return agentScheduleTaskDao.queryListByUserId(userId);
    }

    /** 查询指定定时配置生成的运行时任务。 */
    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByConfigId(String configId) {
        return agentScheduleTaskDao.queryListByConfigId(configId);
    }

    /** 查询指定业务任务标识对应的运行时任务记录。 */
    public List<AgentScheduleTaskPO> queryAgentScheduleTaskListByTaskId(String taskId) {
        return agentScheduleTaskDao.queryListByTaskId(taskId);
    }

    /** 新增调度执行记录。 */
    public int insertAgentScheduleExecution(AgentScheduleExecutionPO agentScheduleExecution) {
        return agentScheduleExecutionDao.insert(agentScheduleExecution);
    }

    /** 按数据库主键更新调度执行记录。 */
    public int updateAgentScheduleExecutionById(AgentScheduleExecutionPO agentScheduleExecution) {
        return agentScheduleExecutionDao.updateById(agentScheduleExecution);
    }

    /** 按数据库主键查询调度执行记录。 */
    public AgentScheduleExecutionPO queryAgentScheduleExecutionById(Long id) {
        return agentScheduleExecutionDao.queryById(id);
    }

    /** 按业务执行标识查询调度执行记录。 */
    public AgentScheduleExecutionPO queryAgentScheduleExecutionByExecutionId(String executionId) {
        return agentScheduleExecutionDao.queryByExecutionId(executionId);
    }

    /** 查询租户范围内的调度执行记录。 */
    public List<AgentScheduleExecutionPO> queryAgentScheduleExecutionListByTenantId(String tenantId) {
        return agentScheduleExecutionDao.queryListByTenantId(tenantId);
    }

    /** 查询指定用户的调度执行记录。 */
    public List<AgentScheduleExecutionPO> queryAgentScheduleExecutionListByUserId(String userId) {
        return agentScheduleExecutionDao.queryListByUserId(userId);
    }

    /** 查询指定运行时任务的执行记录。 */
    public List<AgentScheduleExecutionPO> queryAgentScheduleExecutionListByTaskId(String taskId) {
        return agentScheduleExecutionDao.queryListByTaskId(taskId);
    }
}
