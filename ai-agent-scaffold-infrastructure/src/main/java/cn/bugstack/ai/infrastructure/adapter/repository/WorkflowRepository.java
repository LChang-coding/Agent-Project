package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowMcpToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSkillToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowVersionEntity;
import cn.bugstack.ai.infrastructure.dao.IAgentWorkflowDao;
import cn.bugstack.ai.infrastructure.dao.IAgentWorkflowVersionDao;
import cn.bugstack.ai.infrastructure.dao.IMcpServerConfigDao;
import cn.bugstack.ai.infrastructure.dao.ISkillDefinitionDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentWorkflowPO;
import cn.bugstack.ai.infrastructure.dao.po.AgentWorkflowVersionPO;
import cn.bugstack.ai.infrastructure.dao.po.McpServerConfigPO;
import cn.bugstack.ai.infrastructure.dao.po.SkillDefinitionPO;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工作流仓储实现。
 */
@Repository
public class WorkflowRepository implements IWorkflowRepository {

    /** 工作流定义、状态和当前版本指针的持久化入口。 */
    private final IAgentWorkflowDao agentWorkflowDao;
    /** 工作流图不可变版本的持久化入口。 */
    private final IAgentWorkflowVersionDao agentWorkflowVersionDao;
    /** 解析工作流引用的 MCP 定义和发布版本。 */
    private final IMcpServerConfigDao mcpServerConfigDao;
    /** 解析工作流引用的 Skill 定义和发布版本。 */
    private final ISkillDefinitionDao skillDefinitionDao;
    /** 编解码工作流图 JSON，并兼容旧版本缺少新增字段。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建工作流仓储；参数是工作流、版本、MCP、Skill DAO；返回仓储实例。
     */
    public WorkflowRepository(IAgentWorkflowDao agentWorkflowDao,
                              IAgentWorkflowVersionDao agentWorkflowVersionDao,
                              IMcpServerConfigDao mcpServerConfigDao,
                              ISkillDefinitionDao skillDefinitionDao) {
        this(agentWorkflowDao, agentWorkflowVersionDao, mcpServerConfigDao, skillDefinitionDao,
                new ObjectMapper());
    }

    @Autowired
    /** 注入共享 ObjectMapper，并复制为兼容旧工作流图字段的仓储专用实例。 */
    public WorkflowRepository(IAgentWorkflowDao agentWorkflowDao,
                              IAgentWorkflowVersionDao agentWorkflowVersionDao,
                              IMcpServerConfigDao mcpServerConfigDao,
                              ISkillDefinitionDao skillDefinitionDao,
                              ObjectMapper objectMapper) {
        this.agentWorkflowDao = agentWorkflowDao;
        this.agentWorkflowVersionDao = agentWorkflowVersionDao;
        this.mcpServerConfigDao = mcpServerConfigDao;
        this.skillDefinitionDao = skillDefinitionDao;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 新增工作流；参数是工作流实体；返回影响行数。
     */
    @Override
    public int insertWorkflow(WorkflowEntity workflow) {
        return agentWorkflowDao.insert(toWorkflowPO(workflow));
    }

    /**
     * 更新工作流；参数是工作流实体；返回影响行数。
     */
    @Override
    public int updateWorkflow(WorkflowEntity workflow) {
        return agentWorkflowDao.updateByWorkflowId(toWorkflowPO(workflow));
    }

    /**
     * 查询工作流；参数是租户和工作流ID；返回工作流实体。
     */
    @Override
    public WorkflowEntity queryWorkflow(String tenantId, String workflowId) {
        return toWorkflowEntity(agentWorkflowDao.queryByWorkflowId(tenantId, workflowId));
    }

    /**
     * 查询租户工作流列表；参数是租户ID；返回工作流实体列表。
     */
    @Override
    public List<WorkflowEntity> queryWorkflowList(String tenantId) {
        List<AgentWorkflowPO> list = agentWorkflowDao.queryListByTenantId(tenantId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(this::toWorkflowEntity).collect(Collectors.toList());
    }

    @Override
    /** 在租户范围内软删除工作流，并记录执行删除的用户。 */
    public int softDeleteWorkflow(String tenantId, String workflowId, String deletedBy) {
        return agentWorkflowDao.softDelete(tenantId, workflowId, deletedBy);
    }

    /**
     * 新增版本；参数是版本实体；返回影响行数。
     */
    @Override
    public int insertVersion(WorkflowVersionEntity version) {
        return agentWorkflowVersionDao.insert(toVersionPO(version));
    }

    /**
     * 更新版本；参数是版本实体；返回影响行数。
     */
    @Override
    public int updateVersion(WorkflowVersionEntity version) {
        return agentWorkflowVersionDao.updateByWorkflowVersion(toVersionPO(version));
    }

    /**
     * 查询指定版本；参数是租户、工作流ID和版本；返回版本实体。
     */
    @Override
    public WorkflowVersionEntity queryVersion(String tenantId, String workflowId, Integer version) {
        return toVersionEntity(agentWorkflowVersionDao.queryByWorkflowVersion(tenantId, workflowId, version));
    }

    /**
     * 查询最新草稿版本；参数是租户和工作流ID；返回版本实体。
     */
    @Override
    public WorkflowVersionEntity queryLatestDraft(String tenantId, String workflowId) {
        return toVersionEntity(agentWorkflowVersionDao.queryLatestDraft(tenantId, workflowId));
    }

    /**
     * 查询最新发布版本；参数是租户和工作流ID；返回版本实体。
     */
    @Override
    public WorkflowVersionEntity queryLatestPublished(String tenantId, String workflowId) {
        return toVersionEntity(agentWorkflowVersionDao.queryLatestPublished(tenantId, workflowId));
    }

    /**
     * 查询最大版本号；参数是租户和工作流ID；返回版本号。
     */
    @Override
    public Integer queryMaxVersion(String tenantId, String workflowId) {
        return agentWorkflowVersionDao.queryMaxVersion(tenantId, workflowId);
    }

    /**
     * 查询可用 MCP 选项；参数是租户ID；返回选项列表。
     */
    @Override
    public List<WorkflowOptionEntity> queryMcpOptions(String tenantId) {
        List<McpServerConfigPO> list = mcpServerConfigDao.queryListByTenantId(tenantId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .map(item -> WorkflowOptionEntity.builder()
                        .value(item.getMcpId())
                        .label(item.getMcpName())
                        .description(item.getDescription())
                        .type(item.getTransportType())
                        .status(item.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 查询可用 Skill 选项；参数是租户ID；返回选项列表。
     */
    @Override
    public List<WorkflowOptionEntity> querySkillOptions(String tenantId) {
        List<SkillDefinitionPO> list = skillDefinitionDao.queryListByTenantId(tenantId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .map(item -> WorkflowOptionEntity.builder()
                        .value(item.getSkillId())
                        .label(item.getSkillName())
                        .description(item.getDescription())
                        .type(item.getSourceType())
                        .status(item.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 查询 MCP 工具配置；参数是租户ID和 MCP ID；返回工具配置列表。
     */
    @Override
    public List<WorkflowMcpToolEntity> queryMcpTools(String tenantId, List<String> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpServerConfigPO> list = mcpServerConfigDao.queryListByTenantId(tenantId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(item -> mcpIds.contains(item.getMcpId()))
                .filter(item -> "active".equals(item.getStatus()))
                .map(item -> WorkflowMcpToolEntity.builder()
                        .mcpId(item.getMcpId())
                        .mcpName(item.getMcpName())
                        .transportType(item.getTransportType())
                        .endpoint(item.getEndpoint())
                        .command(item.getCommand())
                        .args(item.getArgs())
                        .env(item.getEnv())
                        .build())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 查询 Skill 工具配置；参数是租户ID和 Skill ID；返回工具配置列表。
     */
    @Override
    public List<WorkflowSkillToolEntity> querySkillTools(String tenantId, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SkillDefinitionPO> list = skillDefinitionDao.queryListByTenantId(tenantId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(item -> skillIds.contains(item.getSkillId()))
                .filter(item -> "active".equals(item.getStatus()))
                .map(item -> WorkflowSkillToolEntity.builder()
                        .skillId(item.getSkillId())
                        .skillName(item.getSkillName())
                        .sourceType(item.getSourceType())
                        .sourceUri(item.getSourceUri())
                        .build())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 转换工作流 PO；参数是实体；返回持久化对象。
     */
    private AgentWorkflowPO toWorkflowPO(WorkflowEntity workflow) {
        return AgentWorkflowPO.builder()
                .tenantId(workflow.getTenantId())
                .ownerUserId(workflow.getOwnerUserId())
                .visibility(workflow.getVisibility())
                .workflowId(workflow.getWorkflowId())
                .workflowName(workflow.getWorkflowName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .defaultModelCode(workflow.getDefaultModelCode())
                .currentVersion(workflow.getCurrentVersion())
                .publishedVersion(workflow.getPublishedVersion())
                .build();
    }

    /**
     * 转换工作流实体；参数是持久化对象；返回实体。
     */
    private WorkflowEntity toWorkflowEntity(AgentWorkflowPO workflow) {
        if (workflow == null) {
            return null;
        }
        return WorkflowEntity.builder()
                .tenantId(workflow.getTenantId())
                .ownerUserId(workflow.getOwnerUserId())
                .visibility(workflow.getVisibility())
                .workflowId(workflow.getWorkflowId())
                .workflowName(workflow.getWorkflowName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .defaultModelCode(workflow.getDefaultModelCode())
                .currentVersion(workflow.getCurrentVersion())
                .publishedVersion(workflow.getPublishedVersion())
                .build();
    }

    /**
     * 转换版本 PO；参数是版本实体；返回持久化对象。
     */
    private AgentWorkflowVersionPO toVersionPO(WorkflowVersionEntity version) {
        return AgentWorkflowVersionPO.builder()
                .tenantId(version.getTenantId())
                .workflowId(version.getWorkflowId())
                .version(version.getVersion())
                .versionStatus(version.getVersionStatus())
                .defaultModelCode(version.getDefaultModelCode())
                .graphJson(toJson(version.getGraph()))
                .createdBy(version.getCreatedBy())
                .publishedBy(version.getPublishedBy())
                .publishedTime(version.getPublishedTime())
                .build();
    }

    /**
     * 转换版本实体；参数是持久化对象；返回版本实体。
     */
    private WorkflowVersionEntity toVersionEntity(AgentWorkflowVersionPO version) {
        if (version == null) {
            return null;
        }
        return WorkflowVersionEntity.builder()
                .tenantId(version.getTenantId())
                .workflowId(version.getWorkflowId())
                .version(version.getVersion())
                .versionStatus(version.getVersionStatus())
                .defaultModelCode(version.getDefaultModelCode())
                .graph(fromJson(version.getGraphJson()))
                .createdBy(version.getCreatedBy())
                .publishedBy(version.getPublishedBy())
                .publishedTime(version.getPublishedTime())
                .build();
    }

    /**
     * 序列化画布；参数是画布对象；返回 JSON 字符串。
     */
    private String toJson(WorkflowGraphEntity graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (JsonProcessingException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流画布序列化失败");
        }
    }

    /**
     * 反序列化画布；参数是 JSON 字符串；返回画布对象。
     */
    private WorkflowGraphEntity fromJson(String graphJson) {
        try {
            return objectMapper.readValue(graphJson, WorkflowGraphEntity.class);
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流画布解析失败", e);
        }
    }
}
