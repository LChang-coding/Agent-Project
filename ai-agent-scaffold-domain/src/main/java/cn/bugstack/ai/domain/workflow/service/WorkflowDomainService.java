package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagCompileResultEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowCreateCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDetailEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeOptionsEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowOptionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSaveDraftCommandEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowVersionEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 管理草稿/发布生命周期，并按发布版本懒编译动态运行时 Agent。 */
@Service
public class WorkflowDomainService implements IWorkflowService {

    /** 定义或版本尚未发布。 */
    private static final String STATUS_DRAFT = "draft";
    /** 定义或版本可运行。 */
    private static final String STATUS_PUBLISHED = "published";
    /** 仅所有者和管理员可读。 */
    private static final String VISIBILITY_PRIVATE = "private";
    /** 当前租户成员可读，仍只有所有者/管理员可写。 */
    private static final String VISIBILITY_TENANT_PUBLIC = "tenant_public";

    /** 持久化定义、版本、图和工具选项。 */
    @Resource
    private IWorkflowRepository workflowRepository;

    /** 解析并校验模型覆盖。 */
    @Resource
    private ModelRouter modelRouter;

    /** 将发布图编译为节点装配表和 DAG 计划。 */
    @Resource
    private WorkflowRuntimeCompiler workflowRuntimeCompiler;

    /** 首次加载运行时时装配动态节点 Agent。 */
    @Resource
    private IArmoryService armoryService;

    /** 删除前检查未完成的工作流运行。 */
    @Resource
    private IChatRunRepository chatRunRepository;

    /** 以租户、工作流、版本和有效模型缓存不可变运行时。 */
    private final ConcurrentMap<String, WorkflowRuntimeEntity> runtimeCache = new ConcurrentHashMap<>();

    /**
     * 查询工作流列表；参数是租户ID；返回工作流列表。
     */
    @Override
    public List<WorkflowEntity> queryWorkflowList(String tenantId, String userId, String roleCode) {
        checkTenant(tenantId);
        checkUser(userId);
        return workflowRepository.queryWorkflowList(tenantId).stream()
                .filter(workflow -> isReadable(workflow, userId, roleCode))
                .toList();
    }

    /** 同时创建定义和版本 1 草稿；默认图提供一个可编辑根节点。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowEntity createWorkflow(WorkflowCreateCommandEntity command) {
        checkCreateCommand(command);
        String workflowId = "wf_" + UUID.randomUUID();
        String modelCode = modelRouter.route(null, null, command.getDefaultModelCode());
        WorkflowEntity workflow = WorkflowEntity.builder()
                .tenantId(command.getTenantId())
                .ownerUserId(command.getUserId())
                .visibility(defaultString(command.getVisibility(), VISIBILITY_PRIVATE))
                .workflowId(workflowId)
                .workflowName(command.getWorkflowName().trim())
                .description(command.getDescription())
                .status(STATUS_DRAFT)
                .defaultModelCode(modelCode)
                .currentVersion(1)
                .publishedVersion(0)
                .build();
        workflowRepository.insertWorkflow(workflow);
        workflowRepository.insertVersion(WorkflowVersionEntity.builder()
                .tenantId(command.getTenantId())
                .workflowId(workflowId)
                .version(1)
                .versionStatus(STATUS_DRAFT)
                .defaultModelCode(modelCode)
                .graph(defaultGraph(modelCode))
                .createdBy(command.getUserId())
                .build());
        AiLog.info(AiLog.workflow().created(command.getTenantId(), command.getUserId(), workflowId, workflow.getWorkflowName(), modelCode));
        return workflow;
    }

    /**
     * 查询工作流详情；参数是租户和工作流ID；返回工作流详情。
     */
    @Override
    public WorkflowDetailEntity queryWorkflowDetail(String tenantId, String userId, String roleCode, String workflowId) {
        checkUser(userId);
        WorkflowEntity workflow = requireReadableWorkflow(tenantId, userId, roleCode, workflowId);
        WorkflowVersionEntity version = workflowRepository.queryLatestDraft(tenantId, workflowId);
        if (version == null) {
            version = workflowRepository.queryLatestPublished(tenantId, workflowId);
        }
        return WorkflowDetailEntity.builder().workflow(workflow).version(version).build();
    }

    /** 更新现有草稿或创建下一草稿版本，不修改 publishedVersion。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDetailEntity saveDraft(WorkflowSaveDraftCommandEntity command) {
        checkSaveCommand(command);
        WorkflowEntity workflow = requireWorkflow(command.getTenantId(), command.getWorkflowId());
        assertWritable(workflow, command.getUserId(), command.getRoleCode());
        String modelCode = modelRouter.route(null, null, command.getDefaultModelCode());
        WorkflowVersionEntity draft = workflowRepository.queryLatestDraft(command.getTenantId(), command.getWorkflowId());
        if (draft == null) {
            int nextVersion = safeVersion(workflowRepository.queryMaxVersion(command.getTenantId(), command.getWorkflowId())) + 1;
            draft = WorkflowVersionEntity.builder()
                    .tenantId(command.getTenantId())
                    .workflowId(command.getWorkflowId())
                    .version(nextVersion)
                    .versionStatus(STATUS_DRAFT)
                    .createdBy(command.getUserId())
                    .build();
            workflowRepository.insertVersion(fillDraft(draft, command, modelCode));
        } else {
            workflowRepository.updateVersion(fillDraft(draft, command, modelCode));
        }

        workflow.setWorkflowName(command.getWorkflowName().trim());
        workflow.setDescription(command.getDescription());
        workflow.setVisibility(defaultString(command.getVisibility(), workflow.getVisibility()));
        workflow.setDefaultModelCode(modelCode);
        workflow.setCurrentVersion(draft.getVersion());
        workflow.setStatus(STATUS_DRAFT);
        workflowRepository.updateWorkflow(workflow);
        // 草稿变化不影响既有发布版本，但清缓存保证后续显式版本加载不复用旧编译结果。
        runtimeCache.keySet().removeIf(key -> key.contains(":" + command.getWorkflowId() + ":"));
        AiLog.info(AiLog.workflow().draftSaved(command.getTenantId(), command.getUserId(), command.getWorkflowId(), draft.getVersion()));
        return queryWorkflowDetail(command.getTenantId(), command.getUserId(), command.getRoleCode(), command.getWorkflowId());
    }

    /** 校验图结构后推进发布指针；具体 Agent 装配在首次 loadRuntime 时执行。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDetailEntity publishWorkflow(String tenantId, String userId, String roleCode, String workflowId) {
        checkTenant(tenantId);
        checkUser(userId);
        WorkflowEntity workflow = requireWorkflow(tenantId, workflowId);
        assertWritable(workflow, userId, roleCode);
        WorkflowVersionEntity draft = workflowRepository.queryLatestDraft(tenantId, workflowId);
        if (draft == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "没有可发布的草稿版本");
        }
        validateGraph(draft.getGraph());
        draft.setVersionStatus(STATUS_PUBLISHED);
        draft.setPublishedBy(userId);
        draft.setPublishedTime(LocalDateTime.now());
        workflowRepository.updateVersion(draft);

        workflow.setStatus(STATUS_PUBLISHED);
        workflow.setPublishedVersion(draft.getVersion());
        workflow.setCurrentVersion(draft.getVersion());
        workflow.setDefaultModelCode(draft.getDefaultModelCode());
        workflowRepository.updateWorkflow(workflow);
        runtimeCache.keySet().removeIf(key -> key.contains(":" + workflowId + ":"));
        AiLog.info(AiLog.workflow().published(tenantId, userId, workflowId, draft.getVersion()));
        return WorkflowDetailEntity.builder().workflow(workflow).version(draft).build();
    }

    /**
     * 查询节点选项；参数是租户ID；返回节点、模型和工具选项。
     */
    @Override
    public WorkflowNodeOptionsEntity queryNodeOptions(String tenantId) {
        checkTenant(tenantId);
        return WorkflowNodeOptionsEntity.builder()
                .nodeTypes(nodeTypeOptions())
                .models(modelRouter.modelOptions())
                .mcpServers(workflowRepository.queryMcpOptions(tenantId))
                .skills(workflowRepository.querySkillOptions(tenantId))
                .build();
    }

    /** 只加载发布版本，并按版本与有效模型组合懒编译缓存。 */
    @Override
    public WorkflowRuntimeEntity loadRuntime(String tenantId, String userId, String roleCode, String workflowId,
                                             Integer workflowVersion, String requestModelCode) {
        checkTenant(tenantId);
        checkUser(userId);
        WorkflowEntity workflow = requireReadableWorkflow(tenantId, userId, roleCode, workflowId);
        if (!STATUS_PUBLISHED.equals(workflow.getStatus())) {
            throw new AppException("WORKFLOW_NOT_RUNNABLE", "工作流未发布或已停用");
        }
        WorkflowVersionEntity version = resolveRuntimeVersion(tenantId, workflowId, workflowVersion);
        String effectiveModelCode = modelRouter.route(requestModelCode, null, version.getDefaultModelCode());
        String cacheKey = tenantId + ":" + workflowId + ":" + version.getVersion() + ":" + (isBlank(requestModelCode) ? "configured" : effectiveModelCode);
        AiLog.info(AiLog.workflow().modelRouted(tenantId, userId, workflowId, effectiveModelCode));
        // 不同请求模型覆盖必须拥有独立 Runner；同一组合只装配一次。
        return runtimeCache.computeIfAbsent(cacheKey, key -> compileRuntime(tenantId, userId, workflow, version, requestModelCode, effectiveModelCode));
    }

    /** 软删除工作流；参数是可信租户、用户和工作流；无返回值。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkflow(String tenantId, String userId, String roleCode, String workflowId) {
        checkTenant(tenantId); checkUser(userId);
        WorkflowEntity workflow = workflowRepository.queryWorkflow(tenantId, workflowId);
        if (workflow == null) return;
        assertWritable(workflow, userId, roleCode);
        if (!chatRunRepository.queryExecutableBySource(tenantId, "workflow", workflowId).isEmpty()) {
            throw new AppException("WORKFLOW_ACTIVE_RUN_CONFLICT", "工作流仍有未完成运行，暂时不能删除");
        }
        workflowRepository.softDeleteWorkflow(tenantId, workflowId, userId);
        runtimeCache.keySet().removeIf(key -> key.contains(":" + workflowId + ":"));
    }

    /** 编译所有节点并完成装配后，才把运行时返回给缓存。 */
    private WorkflowRuntimeEntity compileRuntime(String tenantId,
                                                 String userId,
                                                 WorkflowEntity workflow,
                                                 WorkflowVersionEntity version,
                                                 String requestModelCode,
                                                 String effectiveModelCode) {
        try {
            WorkflowDagCompileResultEntity compileResult = workflowRuntimeCompiler.compileDag(workflow, version, requestModelCode);
            // 任一节点装配失败都会阻止该运行时进入缓存。
            armoryService.acceptArmoryAgents(compileResult.getTables());
            WorkflowRuntimeEntity runtime = WorkflowRuntimeEntity.builder()
                    .workflowId(workflow.getWorkflowId())
                    .runtimeAgentId(compileResult.getDagPlan().getNodes().get(0).getRuntimeAgentId())
                    .version(version.getVersion())
                    .effectiveModelCode(effectiveModelCode)
                    .dagPlan(compileResult.getDagPlan())
                    .build();
            AiLog.info(AiLog.workflow().runtimeLoaded(tenantId, userId, workflow.getWorkflowId(), version.getVersion(),
                    runtime.getRuntimeAgentId(), effectiveModelCode));
            return runtime;
        } catch (Exception e) {
            AiLog.error(AiLog.workflow().runFailed(tenantId, userId, workflow.getWorkflowId(), e));
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "工作流运行时加载失败：" + e.getMessage());
        }
    }

    /** 未指定时取最新发布版本；显式版本也必须处于 published。 */
    private WorkflowVersionEntity resolveRuntimeVersion(String tenantId, String workflowId, Integer workflowVersion) {
        WorkflowVersionEntity version = workflowVersion == null
                ? workflowRepository.queryLatestPublished(tenantId, workflowId)
                : workflowRepository.queryVersion(tenantId, workflowId, workflowVersion);
        if (version == null || !STATUS_PUBLISHED.equals(version.getVersionStatus())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流未发布或版本不可运行");
        }
        return version;
    }

    /**
     * 填充草稿版本；参数是草稿、命令和模型；返回版本实体。
     */
    private WorkflowVersionEntity fillDraft(WorkflowVersionEntity draft, WorkflowSaveDraftCommandEntity command, String modelCode) {
        validateGraph(command.getGraph());
        draft.setDefaultModelCode(modelCode);
        draft.setGraph(command.getGraph());
        draft.setVersionStatus(STATUS_DRAFT);
        draft.setCreatedBy(command.getUserId());
        return draft;
    }

    /**
     * 查询工作流；参数是租户和工作流ID；不存在时抛出异常。
     */
    private WorkflowEntity requireWorkflow(String tenantId, String workflowId) {
        checkTenant(tenantId);
        if (isBlank(workflowId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流ID不能为空");
        }
        WorkflowEntity workflow = workflowRepository.queryWorkflow(tenantId, workflowId);
        if (workflow == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流不存在");
        }
        return workflow;
    }

    /** 查询可读工作流；无权访问时与不存在返回相同错误，避免泄露私有工作流。 */
    private WorkflowEntity requireReadableWorkflow(String tenantId, String userId, String roleCode, String workflowId) {
        WorkflowEntity workflow = requireWorkflow(tenantId, workflowId);
        if (!isReadable(workflow, userId, roleCode)) {
            throw workflowNotFound();
        }
        return workflow;
    }

    /** 判断工作流是否可读：本人、租户 owner/admin 或租户公开。 */
    private boolean isReadable(WorkflowEntity workflow, String userId, String roleCode) {
        if (workflow == null) return false;
        boolean owner = userId != null && userId.equals(workflow.getOwnerUserId());
        boolean admin = "owner".equalsIgnoreCase(roleCode) || "admin".equalsIgnoreCase(roleCode);
        boolean tenantPublic = VISIBILITY_TENANT_PUBLIC.equalsIgnoreCase(workflow.getVisibility());
        return owner || admin || tenantPublic;
    }

    private AppException workflowNotFound() {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流不存在");
    }

    private void assertWritable(WorkflowEntity workflow, String userId, String roleCode) {
        boolean owner = workflow != null && userId != null && userId.equals(workflow.getOwnerUserId());
        boolean admin = "owner".equalsIgnoreCase(roleCode) || "admin".equalsIgnoreCase(roleCode);
        if (!owner && !admin) {
            throw new AppException("WORKFLOW_WRITE_PERMISSION_DENIED", "只有工作流拥有者或租户管理员可以操作");
        }
    }

    /**
     * 构建默认画布；参数是模型编码；返回单节点画布。
     */
    private WorkflowGraphEntity defaultGraph(String modelCode) {
        WorkflowGraphEntity.Node node = WorkflowGraphEntity.Node.builder()
                .nodeId("node_start")
                .nodeType("llm")
                .name("智能体节点")
                .description("默认 LLM 节点")
                .instruction("你是一个企业智能体，请根据用户输入给出清晰、可靠的回答。")
                .modelCode(modelCode)
                .mcpIds(Collections.emptyList())
                .skillIds(Collections.emptyList())
                .maxIterations(3)
                .x(120)
                .y(160)
                .build();
        return WorkflowGraphEntity.builder()
                .mode("sequential")
                .rootNodeId(node.getNodeId())
                .nodes(List.of(node))
                .edges(Collections.emptyList())
                .build();
    }

    /**
     * 查询节点类型选项；无参数；返回节点类型列表。
     */
    private List<WorkflowOptionEntity> nodeTypeOptions() {
        return List.of(
                WorkflowOptionEntity.builder().value("llm").label("LLM 节点").description("调用模型并可挂载 MCP/Skill").type("runtime").status("active").build(),
                WorkflowOptionEntity.builder().value("sequential").label("串行").description("按节点顺序依次执行").type("control").status("active").build(),
                WorkflowOptionEntity.builder().value("parallel").label("并行").description("多个节点并行执行后汇总").type("control").status("active").build(),
                WorkflowOptionEntity.builder().value("loop").label("循环").description("按最大次数重复执行子节点").type("control").status("active").build()
        );
    }

    /**
     * 校验创建命令；参数是命令；非法时抛出异常。
     */
    private void checkCreateCommand(WorkflowCreateCommandEntity command) {
        if (command == null || isBlank(command.getTenantId()) || isBlank(command.getUserId()) || isBlank(command.getWorkflowName())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户、用户和工作流名称不能为空");
        }
    }

    /**
     * 校验保存命令；参数是命令；非法时抛出异常。
     */
    private void checkSaveCommand(WorkflowSaveDraftCommandEntity command) {
        if (command == null || isBlank(command.getTenantId()) || isBlank(command.getUserId())
                || isBlank(command.getWorkflowId()) || isBlank(command.getWorkflowName())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户、用户、工作流ID和名称不能为空");
        }
    }

    /**
     * 校验画布；参数是画布；非法时抛出异常。
     */
    private void validateGraph(WorkflowGraphEntity graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流画布不能为空");
        }
        boolean hasLlm = graph.getNodes().stream().anyMatch(node -> "llm".equalsIgnoreCase(node.getNodeType()));
        if (!hasLlm) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流至少需要一个 LLM 节点");
        }
    }

    /**
     * 校验租户；参数是租户ID；非法时抛出异常。
     */
    private void checkTenant(String tenantId) {
        if (isBlank(tenantId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "租户ID不能为空");
        }
    }

    /**
     * 校验用户；参数是用户ID；非法时抛出异常。
     */
    private void checkUser(String userId) {
        if (isBlank(userId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID不能为空");
        }
    }

    /**
     * 安全版本号；参数是版本号；返回非空版本号。
     */
    private int safeVersion(Integer version) {
        return version == null ? 0 : version;
    }

    /**
     * 字符串默认值；参数是候选值和默认值；返回非空字符串。
     */
    private String defaultString(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    /**
     * 判断字符串为空；参数是字符串；返回是否为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
