package cn.bugstack.ai.domain.workflow.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagCompileResultEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowGraphEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowMcpToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowSkillToolEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowVersionEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 将数据库图编译为每节点 Agent 装配表和 ChatService 可执行 DAG 计划。 */
@Service
public class WorkflowRuntimeCompiler {

    /** 动态 Spring Bean/ADK Agent 名称硬上限。 */
    private static final int MAX_AGENT_NAME_LENGTH = 80;

    /** 复制系统已有 AI API 通道，不在工作流保存密钥。 */
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /** 解析工作流引用的已发布 Skill/MCP。 */
    @Resource
    private IWorkflowRepository workflowRepository;

    /** 解析请求、节点和工作流默认模型。 */
    @Resource
    private ModelRouter modelRouter;

    /**
     * 编译工作流；参数是工作流、版本和请求模型；返回可由 Armory 装配的配置表。
     */
    public AiAgentConfigTableVO compile(WorkflowEntity workflow, WorkflowVersionEntity version, String requestModelCode) {
        checkRuntimeSource(workflow, version);
        WorkflowGraphEntity graph = version.getGraph();
        List<WorkflowGraphEntity.Node> llmNodes = llmNodes(graph);
        if (llmNodes.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流至少需要一个 LLM 节点");
        }

        String runtimeAgentId = runtimeAgentId(workflow.getWorkflowId(), version.getVersion(), requestModelCode);
        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        table.setAppName(runtimeAgentId);
        table.setAgent(buildAgent(workflow, runtimeAgentId));

        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        module.setAiApi(copyAiApi());
        module.setChatModel(buildGlobalChatModel(version, requestModelCode));
        module.setAgents(buildLlmAgents(workflow.getTenantId(), version, requestModelCode, llmNodes));
        module.setAgentWorkflows(buildAgentWorkflows(graph, llmNodes));
        module.setRunner(buildRunner(graph, llmNodes));
        table.setModule(module);
        return table;
    }

    /** 每个 LLM 节点生成独立 Runner，再输出只包含 LLM 节点的 DAG。 */
    public WorkflowDagCompileResultEntity compileDag(WorkflowEntity workflow, WorkflowVersionEntity version, String requestModelCode) {
        checkRuntimeSource(workflow, version);
        WorkflowGraphEntity graph = version.getGraph();
        List<WorkflowGraphEntity.Node> llmNodes = llmNodes(graph);
        if (llmNodes.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流至少需要一个 LLM 节点");
        }
        // 编译前拒绝非自循环环路，避免运行时 Join 永久等待。
        validateDagGraph(graph, llmNodes);

        String runtimeAgentId = runtimeAgentId(workflow.getWorkflowId(), version.getVersion(), requestModelCode);
        Map<String, WorkflowGraphEntity.Node> llmNodeMap = llmNodes.stream()
                .collect(Collectors.toMap(WorkflowGraphEntity.Node::getNodeId, node -> node, (left, right) -> left, LinkedHashMap::new));

        List<AiAgentConfigTableVO> tables = new ArrayList<>();
        List<WorkflowDagPlanEntity.Node> planNodes = new ArrayList<>();
        for (WorkflowGraphEntity.Node node : llmNodes) {
            // 每个节点单独装配，节点模型覆盖和调用证据互不串用。
            String nodeRuntimeAgentId = runtimeNodeAgentId(runtimeAgentId, node);
            String nodeRuntimeAgentName = nodeAgentName(node);
            tables.add(buildDagNodeTable(workflow, version, requestModelCode, node, nodeRuntimeAgentId, nodeRuntimeAgentName));
            planNodes.add(WorkflowDagPlanEntity.Node.builder()
                    .nodeId(node.getNodeId())
                    .nodeName(defaultString(node.getName(), node.getNodeId()))
                    .description(node.getDescription())
                    .runtimeAgentId(nodeRuntimeAgentId)
                    .runtimeAgentName(nodeRuntimeAgentName)
                    .modelCode(modelRouter.route(requestModelCode, node.getModelCode(), version.getDefaultModelCode()))
                    .maxIterations(loopIterations(node))
                    .build());
        }

        WorkflowDagPlanEntity dagPlan = WorkflowDagPlanEntity.builder()
                .workflowId(workflow.getWorkflowId())
                .version(version.getVersion())
                .rootNodeId(rootDagNodeId(graph, llmNodes))
                .defaultModelCode(modelRouter.route(requestModelCode, null, version.getDefaultModelCode()))
                .nodes(planNodes)
                .edges(dagEdges(graph, llmNodeMap))
                .build();
        return WorkflowDagCompileResultEntity.builder().tables(tables).dagPlan(dagPlan).build();
    }

    /** 工作流、版本和模型覆盖共同决定稳定运行体 ID。 */
    public String runtimeAgentId(String workflowId, Integer version, String requestModelCode) {
        String modelKey = requestModelCode == null || requestModelCode.isBlank() ? "configured" : requestModelCode;
        return safeAgentName("wf_" + workflowId + "_v" + version + "_" + modelKey, "wf_runtime");
    }

    /**
     * 生成 DAG 节点运行时 Agent ID；参数是工作流运行时ID和节点；返回节点 Bean 名称。
     */
    private String runtimeNodeAgentId(String runtimeAgentId, WorkflowGraphEntity.Node node) {
        String uniqueKey = shortHash(runtimeAgentId + ":" + node.getNodeId());
        return safeAgentName("wf_node_" + uniqueKey + "_" + nodeAgentName(node), "wf_node");
    }

    /**
     * 校验编译来源；参数是工作流和版本；非法时抛出异常。
     */
    private void checkRuntimeSource(WorkflowEntity workflow, WorkflowVersionEntity version) {
        if (workflow == null || version == null || version.getGraph() == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流运行配置不存在");
        }
    }

    /**
     * 构建工作流 Agent 元信息；参数是工作流和运行时ID；返回 Agent 配置。
     */
    private AiAgentConfigTableVO.Agent buildAgent(WorkflowEntity workflow, String runtimeAgentId) {
        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId(runtimeAgentId);
        agent.setAgentName(workflow.getWorkflowName());
        agent.setAgentDesc(workflow.getDescription());
        return agent;
    }

    /**
     * 构建 DAG 节点配置表；参数是工作流、版本、模型和节点；返回单节点 Agent 配置。
     */
    private AiAgentConfigTableVO buildDagNodeTable(WorkflowEntity workflow,
                                                   WorkflowVersionEntity version,
                                                   String requestModelCode,
                                                   WorkflowGraphEntity.Node node,
                                                   String runtimeAgentId,
                                                   String runtimeAgentName) {
        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        table.setAppName(runtimeAgentId);

        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId(runtimeAgentId);
        agent.setAgentName(workflow.getWorkflowName() + "/" + defaultString(node.getName(), node.getNodeId()));
        agent.setAgentDesc(defaultString(node.getDescription(), workflow.getDescription()));
        table.setAgent(agent);

        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        module.setAiApi(copyAiApi());
        module.setChatModel(buildGlobalChatModel(version, requestModelCode));
        module.setAgents(List.of(buildDagNodeAgent(workflow.getTenantId(), version, requestModelCode, node, runtimeAgentName)));
        module.setAgentWorkflows(Collections.emptyList());
        module.setRunner(buildNodeRunner(runtimeAgentName));
        table.setModule(module);
        return table;
    }

    /**
     * 构建 DAG 节点 Agent；参数是租户、版本、模型、节点和运行名；返回 Agent 配置。
     */
    private AiAgentConfigTableVO.Module.Agent buildDagNodeAgent(String tenantId,
                                                                WorkflowVersionEntity version,
                                                                String requestModelCode,
                                                                WorkflowGraphEntity.Node node,
                                                                String runtimeAgentName) {
        AiAgentConfigTableVO.Module.Agent agent = new AiAgentConfigTableVO.Module.Agent();
        agent.setName(runtimeAgentName);
        agent.setDescription(defaultString(node.getDescription(), node.getName()));
        agent.setInstruction(defaultInstruction(node));
        agent.setOutputKey(safeAgentName(node.getNodeId(), "node") + "_output");
        agent.setModel(modelRouter.route(requestModelCode, node.getModelCode(), version.getDefaultModelCode()));
        agent.setToolMcpList(Collections.emptyList());
        agent.setToolSkillsList(Collections.emptyList());
        return agent;
    }

    /**
     * 构建单节点 Runner；参数是节点运行名；返回 Runner 配置。
     */
    private AiAgentConfigTableVO.Module.Runner buildNodeRunner(String runtimeAgentName) {
        AiAgentConfigTableVO.Module.Runner runner = new AiAgentConfigTableVO.Module.Runner();
        runner.setAgentName(runtimeAgentName);
        runner.setPluginNameList(List.of("myLogPlugin"));
        return runner;
    }

    /** 复制首个系统模型通道；工作流数据库不持久化 API Key。 */
    private AiAgentConfigTableVO.Module.AiApi copyAiApi() {
        AiAgentConfigTableVO source = firstConfiguredTable();
        AiAgentConfigTableVO.Module.AiApi sourceAiApi = source.getModule().getAiApi();
        AiAgentConfigTableVO.Module.AiApi aiApi = new AiAgentConfigTableVO.Module.AiApi();
        aiApi.setBaseUrl(sourceAiApi.getBaseUrl());
        aiApi.setApiKey(sourceAiApi.getApiKey());
        aiApi.setCompletionsPath(sourceAiApi.getCompletionsPath());
        aiApi.setEmbeddingsPath(sourceAiApi.getEmbeddingsPath());
        return aiApi;
    }

    /**
     * 构建全局模型配置；参数是版本和请求模型；返回 ChatModel 配置。
     */
    private AiAgentConfigTableVO.Module.ChatModel buildGlobalChatModel(WorkflowVersionEntity version, String requestModelCode) {
        AiAgentConfigTableVO.Module.ChatModel chatModel = new AiAgentConfigTableVO.Module.ChatModel();
        chatModel.setModel(modelRouter.route(requestModelCode, null, version.getDefaultModelCode()));
        chatModel.setToolMcpList(Collections.emptyList());
        chatModel.setToolSkillsList(Collections.emptyList());
        return chatModel;
    }

    /**
     * 构建 LLM 节点 Agent；参数是租户、版本、请求模型和节点；返回 Agent 配置列表。
     */
    private List<AiAgentConfigTableVO.Module.Agent> buildLlmAgents(String tenantId,
                                                                   WorkflowVersionEntity version,
                                                                   String requestModelCode,
                                                                   List<WorkflowGraphEntity.Node> llmNodes) {
        List<AiAgentConfigTableVO.Module.Agent> agents = new ArrayList<>();
        for (WorkflowGraphEntity.Node node : llmNodes) {
            AiAgentConfigTableVO.Module.Agent agent = new AiAgentConfigTableVO.Module.Agent();
            agent.setName(nodeAgentName(node));
            agent.setDescription(defaultString(node.getDescription(), node.getName()));
            agent.setInstruction(defaultInstruction(node));
            agent.setOutputKey(safeAgentName(node.getNodeId(), "node") + "_output");
            agent.setModel(modelRouter.route(requestModelCode, node.getModelCode(), version.getDefaultModelCode()));
            agent.setToolMcpList(Collections.emptyList());
            agent.setToolSkillsList(Collections.emptyList());
            agents.add(agent);
        }
        return agents;
    }

    /** 兼容旧式 ADK 组合 Agent 编译；单节点非循环无需额外控制节点。 */
    private List<AiAgentConfigTableVO.Module.AgentWorkflow> buildAgentWorkflows(WorkflowGraphEntity graph,
                                                                                List<WorkflowGraphEntity.Node> llmNodes) {
        String mode = normalizeMode(graph.getMode());
        if (llmNodes.size() <= 1 && !"loop".equals(mode)) {
            return Collections.emptyList();
        }
        AiAgentConfigTableVO.Module.AgentWorkflow workflow = new AiAgentConfigTableVO.Module.AgentWorkflow();
        workflow.setType(mode);
        workflow.setName(rootWorkflowName(graph));
        workflow.setDescription("数据库工作流运行节点");
        workflow.setSubAgents(llmNodes.stream().map(this::nodeAgentName).collect(Collectors.toList()));
        workflow.setMaxIterations(loopIterations(graph));
        return List.of(workflow);
    }

    /**
     * 构建 Runner 配置；参数是画布和 LLM 节点；返回 Runner 配置。
     */
    private AiAgentConfigTableVO.Module.Runner buildRunner(WorkflowGraphEntity graph, List<WorkflowGraphEntity.Node> llmNodes) {
        AiAgentConfigTableVO.Module.Runner runner = new AiAgentConfigTableVO.Module.Runner();
        String mode = normalizeMode(graph.getMode());
        if (llmNodes.size() > 1 || "loop".equals(mode)) {
            runner.setAgentName(rootWorkflowName(graph));
        } else {
            runner.setAgentName(nodeAgentName(llmNodes.get(0)));
        }
        runner.setPluginNameList(List.of("myLogPlugin"));
        return runner;
    }

    /** 将指定 MCP ID 解析为装配配置；当前 DAG 单节点编译未调用此兼容路径。 */
    private List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> buildMcpTools(String tenantId, List<String> mcpIds) {
        List<WorkflowMcpToolEntity> tools = workflowRepository.queryMcpTools(tenantId, mcpIds);
        if (tools.isEmpty()) {
            return Collections.emptyList();
        }
        return tools.stream().map(this::toToolMcp).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /** 将指定 Skill ID 解析为装配配置；当前 DAG 单节点编译未调用此兼容路径。 */
    private List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> buildSkillTools(String tenantId, List<String> skillIds) {
        List<WorkflowSkillToolEntity> tools = workflowRepository.querySkillTools(tenantId, skillIds);
        if (tools.isEmpty()) {
            return Collections.emptyList();
        }
        return tools.stream().map(this::toToolSkill).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * 转换 MCP 配置；参数是工作流 MCP 工具；返回 Armory MCP 配置。
     */
    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp toToolMcp(WorkflowMcpToolEntity tool) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        String type = defaultString(tool.getTransportType(), "local");
        if ("sse".equalsIgnoreCase(type) || "http".equalsIgnoreCase(type)) {
            toolMcp.setSse(toSse(tool));
            return toolMcp;
        }
        if ("stdio".equalsIgnoreCase(type)) {
            toolMcp.setStdio(toStdio(tool));
            return toolMcp;
        }
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters();
        local.setName(tool.getMcpName());
        toolMcp.setLocal(local);
        return toolMcp;
    }

    /**
     * 转换 SSE MCP；参数是 MCP 工具；返回 SSE 配置。
     */
    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters toSse(WorkflowMcpToolEntity tool) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sse = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters();
        sse.setName(tool.getMcpName());
        String endpoint = tool.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return sse;
        }
        try {
            URL url = new URL(endpoint);
            String port = url.getPort() > 0 ? ":" + url.getPort() : "";
            String path = url.getPath();
            int split = path.lastIndexOf('/');
            String basePath = split >= 0 ? path.substring(0, split + 1) : "/";
            String endpointPath = split >= 0 ? path.substring(split + 1) : path;
            if (url.getQuery() != null && !url.getQuery().isBlank()) {
                endpointPath = endpointPath + "?" + url.getQuery();
            }
            sse.setBaseUri(url.getProtocol() + "://" + url.getHost() + port + basePath);
            sse.setSseEndpoint(endpointPath);
        } catch (Exception ignored) {
            sse.setBaseUri(endpoint);
            sse.setSseEndpoint("");
        }
        return sse;
    }

    /**
     * 转换 stdio MCP；参数是 MCP 工具；返回 stdio 配置。
     */
    private AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters toStdio(WorkflowMcpToolEntity tool) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters stdio = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters();
        stdio.setName(tool.getMcpName());
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters server = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters();
        server.setCommand(tool.getCommand());
        server.setArgs(splitArgs(tool.getArgs()));
        server.setEnv(parseEnv(tool.getEnv()));
        stdio.setServerParameters(server);
        return stdio;
    }

    /**
     * 转换 Skill 配置；参数是工作流 Skill 工具；返回 Armory Skill 配置。
     */
    private AiAgentConfigTableVO.Module.ChatModel.ToolSkills toToolSkill(WorkflowSkillToolEntity tool) {
        if (tool.getSourceUri() == null || tool.getSourceUri().isBlank()) {
            return null;
        }
        AiAgentConfigTableVO.Module.ChatModel.ToolSkills skill = new AiAgentConfigTableVO.Module.ChatModel.ToolSkills();
        skill.setType(defaultString(tool.getSourceType(), "directory"));
        skill.setPath(tool.getSourceUri());
        return skill;
    }

    /**
     * 查询第一个系统模型配置；无参数；返回配置表。
     */
    private AiAgentConfigTableVO firstConfiguredTable() {
        if (aiAgentAutoConfigProperties.getTables() == null || aiAgentAutoConfigProperties.getTables().isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "系统模型通道未配置");
        }
        return aiAgentAutoConfigProperties.getTables().values().stream()
                .filter(table -> table.getModule() != null && table.getModule().getAiApi() != null)
                .findFirst()
                .orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "系统模型通道未配置"));
    }

    /** 过滤非 LLM 画布节点，并按可达边关系生成稳定顺序。 */
    private List<WorkflowGraphEntity.Node> llmNodes(WorkflowGraphEntity graph) {
        if (graph.getNodes() == null) {
            return Collections.emptyList();
        }
        List<WorkflowGraphEntity.Node> nodes = graph.getNodes().stream()
                .filter(node -> "llm".equalsIgnoreCase(node.getNodeType()))
                .collect(Collectors.toList());
        return orderNodesByEdges(graph, nodes);
    }

    /** 使用 Kahn 算法拒绝非自循环环路；自循环保留为有限迭代语义。 */
    private void validateDagGraph(WorkflowGraphEntity graph, List<WorkflowGraphEntity.Node> llmNodes) {
        Set<String> nodeIds = llmNodes.stream().map(WorkflowGraphEntity.Node::getNodeId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            indegree.put(nodeId, 0);
            outgoing.put(nodeId, new ArrayList<>());
        }
        if (graph.getEdges() != null) {
            for (WorkflowGraphEntity.Edge edge : graph.getEdges()) {
                if (edge.getSourceNodeId() == null || edge.getTargetNodeId() == null || edge.getSourceNodeId().equals(edge.getTargetNodeId())) {
                    // 自循环不计拓扑入度，由节点 maxIterations 单独执行。
                    continue;
                }
                if (nodeIds.contains(edge.getSourceNodeId()) && nodeIds.contains(edge.getTargetNodeId())) {
                    outgoing.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
                    indegree.put(edge.getTargetNodeId(), indegree.get(edge.getTargetNodeId()) + 1);
                }
            }
        }

        List<String> ready = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
        int visited = 0;
        while (!ready.isEmpty()) {
            String nodeId = ready.remove(0);
            visited++;
            for (String targetNodeId : outgoing.getOrDefault(nodeId, Collections.emptyList())) {
                int next = indegree.get(targetNodeId) - 1;
                indegree.put(targetNodeId, next);
                if (next == 0) {
                    ready.add(targetNodeId);
                }
            }
        }
        if (visited != nodeIds.size()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流暂不支持非自循环环路，请只使用节点指向自身表示循环");
        }
    }

    /** 从根节点深度优先排序，再补齐不可达节点，保证编译结果稳定。 */
    private List<WorkflowGraphEntity.Node> orderNodesByEdges(WorkflowGraphEntity graph, List<WorkflowGraphEntity.Node> nodes) {
        if (nodes.isEmpty() || graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return nodes;
        }
        Map<String, WorkflowGraphEntity.Node> nodeMap = nodes.stream()
                .collect(Collectors.toMap(WorkflowGraphEntity.Node::getNodeId, node -> node, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (WorkflowGraphEntity.Edge edge : graph.getEdges()) {
            if (edge.getSourceNodeId() == null || edge.getTargetNodeId() == null || edge.getSourceNodeId().equals(edge.getTargetNodeId())) {
                continue;
            }
            if (nodeMap.containsKey(edge.getSourceNodeId()) && nodeMap.containsKey(edge.getTargetNodeId())) {
                outgoing.computeIfAbsent(edge.getSourceNodeId(), key -> new ArrayList<>()).add(edge.getTargetNodeId());
            }
        }
        String rootNodeId = nodeMap.containsKey(graph.getRootNodeId()) ? graph.getRootNodeId() : nodes.get(0).getNodeId();
        Set<String> visited = new LinkedHashSet<>();
        List<WorkflowGraphEntity.Node> ordered = new ArrayList<>();
        appendNodeByEdge(rootNodeId, nodeMap, outgoing, visited, ordered);
        for (WorkflowGraphEntity.Node node : nodes) {
            appendNodeByEdge(node.getNodeId(), nodeMap, outgoing, visited, ordered);
        }
        return ordered;
    }

    /**
     * 递归追加节点；参数是节点ID、节点表、出边表、已访问集合和输出列表；无返回值。
     */
    private void appendNodeByEdge(String nodeId,
                                  Map<String, WorkflowGraphEntity.Node> nodeMap,
                                  Map<String, List<String>> outgoing,
                                  Set<String> visited,
                                  List<WorkflowGraphEntity.Node> ordered) {
        if (nodeId == null || !visited.add(nodeId)) {
            return;
        }
        WorkflowGraphEntity.Node node = nodeMap.get(nodeId);
        if (node == null) {
            return;
        }
        ordered.add(node);
        for (String targetNodeId : outgoing.getOrDefault(nodeId, Collections.emptyList())) {
            appendNodeByEdge(targetNodeId, nodeMap, outgoing, visited, ordered);
        }
    }

    /** 优先显式 rootNodeId，否则选择首个零入度节点。 */
    private String rootDagNodeId(WorkflowGraphEntity graph, List<WorkflowGraphEntity.Node> llmNodes) {
        Set<String> nodeIds = llmNodes.stream().map(WorkflowGraphEntity.Node::getNodeId).collect(Collectors.toSet());
        if (nodeIds.contains(graph.getRootNodeId())) {
            return graph.getRootNodeId();
        }
        Map<String, Integer> indegree = llmNodes.stream()
                .collect(Collectors.toMap(WorkflowGraphEntity.Node::getNodeId, node -> 0, (left, right) -> left, LinkedHashMap::new));
        if (graph.getEdges() != null) {
            for (WorkflowGraphEntity.Edge edge : graph.getEdges()) {
                if (edge.getSourceNodeId() == null || edge.getTargetNodeId() == null || edge.getSourceNodeId().equals(edge.getTargetNodeId())) {
                    continue;
                }
                if (indegree.containsKey(edge.getSourceNodeId()) && indegree.containsKey(edge.getTargetNodeId())) {
                    indegree.put(edge.getTargetNodeId(), indegree.get(edge.getTargetNodeId()) + 1);
                }
            }
        }
        return indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(llmNodes.get(0).getNodeId());
    }

    /** 丢弃连接非 LLM 节点的边；保留自循环供运行时识别迭代。 */
    private List<WorkflowDagPlanEntity.Edge> dagEdges(WorkflowGraphEntity graph, Map<String, WorkflowGraphEntity.Node> nodeMap) {
        if (graph.getEdges() == null || graph.getEdges().isEmpty()) {
            return Collections.emptyList();
        }
        List<WorkflowDagPlanEntity.Edge> edges = new ArrayList<>();
        for (WorkflowGraphEntity.Edge edge : graph.getEdges()) {
            if (edge.getSourceNodeId() == null || edge.getTargetNodeId() == null) {
                continue;
            }
            if (nodeMap.containsKey(edge.getSourceNodeId()) && nodeMap.containsKey(edge.getTargetNodeId())) {
                edges.add(WorkflowDagPlanEntity.Edge.builder()
                        .edgeId(defaultString(edge.getEdgeId(), "edge_" + edge.getSourceNodeId() + "_" + edge.getTargetNodeId()))
                        .sourceNodeId(edge.getSourceNodeId())
                        .targetNodeId(edge.getTargetNodeId())
                        .build());
            }
        }
        return edges;
    }

    /**
     * 读取节点 Agent 名称；参数是节点；返回安全名称。
     */
    private String nodeAgentName(WorkflowGraphEntity.Node node) {
        return safeAgentName("node_" + defaultString(node.getNodeId(), ""), "node");
    }

    /**
     * 读取根工作流名称；参数是画布；返回安全名称。
     */
    private String rootWorkflowName(WorkflowGraphEntity graph) {
        return safeAgentName("workflow_" + defaultString(graph.getRootNodeId(), "root"), "workflow_root");
    }

    /**
     * 读取循环次数；参数是画布；返回循环上限。
     */
    private Integer loopIterations(WorkflowGraphEntity graph) {
        if (graph.getNodes() == null) {
            return 3;
        }
        return graph.getNodes().stream()
                .map(WorkflowGraphEntity.Node::getMaxIterations)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(3);
    }

    /**
     * 读取节点循环次数；参数是节点；返回循环上限。
     */
    private Integer loopIterations(WorkflowGraphEntity.Node node) {
        if (node.getMaxIterations() == null || node.getMaxIterations() < 1) {
            return 3;
        }
        return Math.min(node.getMaxIterations(), 20);
    }

    /**
     * 标准化编排模式；参数是模式；返回受支持模式。
     */
    private String normalizeMode(String mode) {
        if ("parallel".equalsIgnoreCase(mode)) {
            return "parallel";
        }
        if ("loop".equalsIgnoreCase(mode)) {
            return "loop";
        }
        return "sequential";
    }

    /**
     * 构建默认提示词；参数是节点；返回提示词。
     */
    private String defaultInstruction(WorkflowGraphEntity.Node node) {
        return defaultString(node.getInstruction(), "你是工作流节点 " + defaultString(node.getName(), node.getNodeId()) + "，请完成当前步骤并输出结果。");
    }

    /**
     * 拆分命令参数；参数是文本；返回参数列表。
     */
    private List<String> splitArgs(String args) {
        if (args == null || args.isBlank()) {
            return Collections.emptyList();
        }
        return List.of(args.trim().split("\\s+"));
    }

    /**
     * 解析环境变量；参数是 k=v 文本；返回环境变量 Map。
     */
    private Map<String, String> parseEnv(String env) {
        if (env == null || env.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : env.split("[,\\n]")) {
            int index = item.indexOf('=');
            if (index > 0) {
                result.put(item.substring(0, index).trim(), item.substring(index + 1).trim());
            }
        }
        return result;
    }

    /**
     * 字符串默认值；参数是候选值和默认值；返回非空字符串。
     */
    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 转安全名称；参数是原始文本；返回适合作为 Agent 名称的文本。
     */
    private String sanitize(String value) {
        return safeAgentName(value, "node");
    }

    /**
     * 生成短哈希；参数是原始文本；返回稳定短标识。
     */
    private String shortHash(String value) {
        String source = value == null ? "" : value;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }

    /**
     * 转 ADK 可接受的 Agent 名称；参数是候选名称和兜底名称；返回稳定 ASCII 名称。
     */
    private String safeAgentName(String value, String fallback) {
        String sanitized = normalizeAgentName(value);
        if (sanitized.isBlank()) {
            sanitized = normalizeAgentName(fallback);
        }
        if (sanitized.isBlank()) {
            sanitized = "node";
        }
        if (sanitized.length() > MAX_AGENT_NAME_LENGTH) {
            sanitized = trimTrailingSeparators(sanitized.substring(0, MAX_AGENT_NAME_LENGTH));
        }
        return sanitized.isBlank() ? "node" : sanitized;
    }

    /**
     * 标准化 Agent 名称字符；参数是原始文本；返回只包含字母数字和下划线的文本。
     */
    private String normalizeAgentName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim()
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("_+", "_");
        return trimTrailingSeparators(normalized.replaceAll("^_+", ""));
    }

    /**
     * 移除尾部分隔符；参数是候选名称；返回不会以分隔符结尾的名称。
     */
    private String trimTrailingSeparators(String value) {
        return value == null ? "" : value.replaceAll("_+$", "");
    }
}
