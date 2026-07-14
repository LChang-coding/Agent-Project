package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.run.model.RunMessageBindingEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowDagPlanEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRuntimeEntity;
import cn.bugstack.ai.domain.workflow.service.IWorkflowService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private SessionDomain sessionDomain;

    @Resource
    private IWorkflowService workflowService;

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @Resource
    private RunControlService runControlService;

    /**
     * 查询 Agent 配置；无参数；返回当前可用 Agent 列表。
     */
    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    /**
     * 创建会话；参数是 Agent ID 和用户ID；返回平台会话ID。
     */
    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        return createSession(agentId, userId, aiAgentRegisterVO);
    }

    /**
     * 创建工作流会话；参数是工作流、版本、模型和用户ID；返回平台会话ID。
     */
    @Override
    public String createWorkflowSession(String workflowId, Integer workflowVersion, String modelCode, String userId) {
        String tenantId = currentTenantId();
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(tenantId, userId, workflowId, workflowVersion, modelCode);
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(runtime.getRuntimeAgentId());
        return createSession(tenantId, workflowId, userId, aiAgentRegisterVO);
    }

    /**
     * 创建平台会话；参数是会话 Agent ID、用户和运行体；返回会话ID。
     */
    private String createSession(String sessionAgentId, String userId, AiAgentRegisterVO aiAgentRegisterVO) {
        return createSession(currentTenantId(), sessionAgentId, userId, aiAgentRegisterVO);
    }

    /**
     * 创建平台会话；参数是租户、会话 Agent ID、用户和运行体；返回会话ID。
     */
    private String createSession(String tenantId, String sessionAgentId, String userId, AiAgentRegisterVO aiAgentRegisterVO) {
        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService().createSession(appName, userId).blockingGet();
        CreateSessionCommandEntity command = new CreateSessionCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(session.id());
        command.setAgentId(sessionAgentId);
        command.setAgentName(aiAgentRegisterVO.getAgentName());
        command.setAppName(appName);
        command.setTitle(aiAgentRegisterVO.getAgentName());
        sessionDomain.createSession(command);
        return session.id();
    }

    /**
     * 发送消息；参数是 Agent ID、用户ID和消息；返回模型回复列表。
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        requireAgent(agentId);

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    /**
     * 发送消息；参数是 Agent ID、用户ID、会话ID和消息；返回模型回复列表。
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);

        return doHandleMessage(agentId, userId, sessionId, message, aiAgentRegisterVO);
    }

    /**
     * 发送工作流消息；参数是工作流、版本、模型、用户、会话和消息；返回模型回复列表。
     */
    @Override
    public List<String> handleWorkflowMessage(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        return List.of(startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId,
                message, null).getStream().blockingFirst());
    }

    /**
     * 流式发送消息；参数是 Agent ID、用户ID、会话ID和消息；返回事件流。
     */
    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return startMessageStream(agentId, userId, sessionId, message, null).getStream();
    }

    /**
     * 创建并启动流式运行；参数是 Agent、可信用户、会话、消息和可选运行ID；返回运行与事件流。
     */
    @Override
    public RunStreamEntity<Event> startMessageStream(String agentId, String userId, String sessionId, String message,
                                                     String requestedRunId) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(agentId, userId, sessionId, aiAgentRegisterVO);
        ChatRunEntity run = runControlService.startOrResume(tenantId, userId, actualSessionId,
                "agent", agentId, requestedRunId);
        String effectiveMessage = steerResumeMessage(run, message);
        return RunStreamEntity.<Event>builder()
                .run(run)
                .stream(doHandleMessageStream(agentId, userId, actualSessionId, effectiveMessage, aiAgentRegisterVO, run))
                .build();
    }

    /**
     * 流式发送工作流消息；参数是工作流、版本、模型、用户、会话和消息；返回事件流。
     */
    @Override
    public Flowable<Event> handleWorkflowMessageStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        return Flowable.error(new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流流式输出请使用文本流接口"));
    }

    /**
     * 流式发送工作流最终文本；参数是工作流、版本、模型、用户、会话和消息；返回最终输出文本流。
     */
    @Override
    public Flowable<String> handleWorkflowMessageTextStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message) {
        return startWorkflowMessageTextStream(workflowId, workflowVersion, modelCode, userId, sessionId, message, null)
                .getStream();
    }

    /**
     * 创建并启动工作流文本运行；参数是工作流身份、会话和可选运行ID；返回运行与文本流。
     */
    @Override
    public RunStreamEntity<String> startWorkflowMessageTextStream(String workflowId, Integer workflowVersion,
                                                                   String modelCode, String userId, String sessionId,
                                                                   String message, String requestedRunId) {
        String tenantId = currentTenantId();
        String traceId = TraceContext.currentOrNewTraceId();
        WorkflowRuntimeEntity runtime = workflowService.loadRuntime(tenantId, userId, workflowId, workflowVersion, modelCode);
        AiAgentRegisterVO rootAgent = requireAgent(runtime.getRuntimeAgentId());
        String actualSessionId = ensureSessionId(tenantId, workflowId, userId, sessionId, rootAgent);
        ChatRunEntity run = runControlService.startOrResume(tenantId, userId, actualSessionId,
                "workflow", workflowId, requestedRunId);
        String effectiveMessage = steerResumeMessage(run, message);
        return RunStreamEntity.<String>builder()
                .run(run)
                .stream(Flowable.fromCallable(() -> doHandleWorkflowDagMessage(runtime, tenantId, userId,
                        actualSessionId, effectiveMessage, traceId, run)).subscribeOn(Schedulers.io()))
                .build();
    }

    /**
     * 发送复合消息；参数是聊天命令；返回模型回复列表。
     */
    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(chatCommandEntity.getAgentId());

        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(chatCommandEntity.getAgentId(), chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());
        sessionDomain.assertSessionAccess(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId());

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String traceId = TraceContext.currentOrNewTraceId();
        ChatRunEntity run = runControlService.start(tenantId, chatCommandEntity.getUserId(), actualSessionId,
                "agent", chatCommandEntity.getAgentId(), null, null);
        conversationMemoryService.republishUnfinished(tenantId, chatCommandEntity.getUserId(), actualSessionId);
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, chatCommandEntity.getUserId(), run.getRunId(),
                describeContent(chatCommandEntity), traceId);
        ChatMessageEntity userMessage = binding.getMessage();
        ChatRunEntity activeRun = binding.getRun();
        String adkSessionId = invocationSessionId(actualSessionId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), chatCommandEntity.getUserId(), adkSessionId);

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), adkSessionId, content, RunConfig.builder().build(),
                runtimeStateDelta(tenantId, chatCommandEntity.getUserId(), actualSessionId, chatCommandEntity.getAgentId(), traceId,
                        TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                        activeRun.getRunId(), activeRun.getCurrentContextRevision()));

        List<String> outputs = new ArrayList<>();
        try {
            events.blockingForEach(event -> {
                runControlService.requireExecutable(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(), null);
                outputs.add(event.stringifyContent());
            });
            completeRunWithAssistant(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(),
                    String.join("\n", outputs), traceId);
        } catch (RuntimeException e) {
            if (!runControlService.cancelled(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId())) {
                failRunWithAssistantError(tenantId, chatCommandEntity.getUserId(), activeRun.getRunId(), traceId, e,
                        String.join("\n", outputs));
            }
            throw e;
        }

        return outputs;
    }

    /**
     * 执行非流式对话；参数是会话 Agent、用户、会话、消息和运行体；返回模型回复列表。
     */
    private List<String> doHandleMessage(String sessionAgentId, String userId, String sessionId, String message, AiAgentRegisterVO aiAgentRegisterVO) {
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(sessionAgentId, userId, sessionId, aiAgentRegisterVO);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, sessionAgentId);
        String traceId = TraceContext.currentOrNewTraceId();
        ChatRunEntity run = runControlService.start(tenantId, userId, actualSessionId, "agent", sessionAgentId,
                null, null);
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId);
        ChatMessageEntity userMessage = binding.getMessage();
        ChatRunEntity activeRun = binding.getRun();
        String adkSessionId = invocationSessionId(actualSessionId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, adkSessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, adkSessionId, userMsg, RunConfig.builder().build(),
                runtimeStateDelta(tenantId, userId, actualSessionId, sessionAgentId, traceId,
                        TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                        activeRun.getRunId(), activeRun.getCurrentContextRevision()));

        List<String> outputs = new ArrayList<>();
        try {
            events.blockingForEach(event -> {
                runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
                outputs.add(event.stringifyContent());
            });
            completeRunWithAssistant(tenantId, userId, activeRun.getRunId(), String.join("\n", outputs), traceId);
        } catch (RuntimeException e) {
            if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                failRunWithAssistantError(tenantId, userId, activeRun.getRunId(), traceId, e, String.join("\n", outputs));
            }
            throw e;
        }

        return outputs;
    }

    /**
     * 执行流式对话；参数是会话 Agent、用户、会话、消息和运行体；返回事件流。
     */
    private Flowable<Event> doHandleMessageStream(String sessionAgentId, String userId, String sessionId, String message,
                                                  AiAgentRegisterVO aiAgentRegisterVO, ChatRunEntity run) {
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String tenantId = currentTenantId();
        String actualSessionId = ensureSessionId(sessionAgentId, userId, sessionId, aiAgentRegisterVO);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, sessionAgentId);
        String traceId = TraceContext.currentOrNewTraceId();
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId);
        ChatMessageEntity userMessage = binding.getMessage();
        run = binding.getRun();
        String adkSessionId = invocationSessionId(actualSessionId);
        ensureAdkSession(runner, aiAgentRegisterVO.getAppName(), userId, adkSessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean assistantSaved = new AtomicBoolean(false);
        ChatRunEntity activeRun = run;
        return runner.runAsync(userId, adkSessionId, userMsg, runConfig,
                        runtimeStateDelta(tenantId, userId, actualSessionId, sessionAgentId, traceId,
                                TenantContextHolder.getRoleCode(), historyCutoff(userMessage), null,
                                activeRun.getRunId(), activeRun.getCurrentContextRevision()))
                .takeUntil(Flowable.interval(250, TimeUnit.MILLISECONDS)
                        .filter(tick -> runControlService.cancelled(tenantId, userId, activeRun.getRunId())))
                .doOnNext(event -> {
                    runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
                    appendContent(assistantContent, event.stringifyContent());
                })
                .doOnComplete(() -> {
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        completeRunWithAssistantOnce(assistantSaved, tenantId, userId, activeRun.getRunId(),
                                assistantContent.toString(), traceId);
                    }
                })
                .doOnError(throwable -> {
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        failRunWithAssistantErrorOnce(assistantSaved, tenantId, userId, activeRun.getRunId(), traceId,
                                throwable, assistantContent.toString());
                    }
                })
                .doOnCancel(() -> {
                    if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                        runControlService.cancel(tenantId, userId, activeRun.getRunId(), "流式连接已中断");
                    }
                });
    }

    /**
     * 执行 DAG 工作流对话；参数是运行时、用户、会话和消息；返回最终节点输出。
     */
    private String doHandleWorkflowDagMessage(WorkflowRuntimeEntity runtime, String tenantId, String userId,
                                              String sessionId, String message, String traceId, ChatRunEntity run) {
        WorkflowDagPlanEntity dagPlan = requireDagPlan(runtime);
        AiAgentRegisterVO rootAgent = requireAgent(runtime.getRuntimeAgentId());
        String actualSessionId = ensureSessionId(tenantId, runtime.getWorkflowId(), userId, sessionId, rootAgent);
        sessionDomain.assertSessionAccess(tenantId, userId, actualSessionId, runtime.getWorkflowId());
        conversationMemoryService.republishUnfinished(tenantId, userId, actualSessionId);
        RunMessageBindingEntity binding = saveRunUserMessage(tenantId, userId, run.getRunId(), message, traceId);
        ChatMessageEntity userMessage = binding.getMessage();
        ChatRunEntity activeRun = binding.getRun();

        try {
            String finalOutput = executeDagPlan(dagPlan, tenantId, userId, actualSessionId, message, traceId,
                    TenantContextHolder.getRoleCode(), historyCutoff(userMessage), activeRun);
            AiLog.info(AiLog.workflow().dagCompleted(tenantId, userId, dagPlan.getWorkflowId(), dagPlan.getVersion(),
                    dagPlan.getNodes().size(), dagPlan.getEdges() == null ? 0 : dagPlan.getEdges().size(),
                    String.join(",", terminalNodeIds(dagPlan, outgoingEdges(dagPlan))), finalOutput.length()));
            runControlService.requireExecutable(tenantId, userId, activeRun.getRunId(), null);
            completeRunWithAssistant(tenantId, userId, activeRun.getRunId(), finalOutput, traceId);
            return finalOutput;
        } catch (RuntimeException e) {
            if (!runControlService.cancelled(tenantId, userId, activeRun.getRunId())) {
                failRunWithAssistantError(tenantId, userId, activeRun.getRunId(), traceId, e, "");
            }
            throw e;
        }
    }

    /**
     * 执行 DAG 计划；参数是计划、用户、会话、用户消息和链路ID；返回终点节点输出。
     */
    private String executeDagPlan(WorkflowDagPlanEntity dagPlan, String tenantId, String userId, String sessionId,
                                  String userMessage, String traceId, String roleCode, Integer historyCutoffSequence,
                                  ChatRunEntity run) {
        Map<String, WorkflowDagPlanEntity.Node> nodeMap = dagPlan.getNodes().stream()
                .collect(Collectors.toMap(WorkflowDagPlanEntity.Node::getNodeId, node -> node, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<String>> outgoing = outgoingEdges(dagPlan);
        Map<String, List<String>> incoming = incomingEdges(dagPlan);
        Map<String, Integer> indegree = indegree(dagPlan);
        Set<String> selfLoopNodeIds = selfLoopNodeIds(dagPlan);
        List<String> ready = dagPlan.getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getNodeId)
                .filter(nodeId -> indegree.getOrDefault(nodeId, 0) == 0)
                .collect(Collectors.toCollection(ArrayList::new));
        if (ready.isEmpty() && dagPlan.getRootNodeId() != null) {
            ready.add(dagPlan.getRootNodeId());
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        while (!ready.isEmpty()) {
            runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
            List<String> currentLevel = new ArrayList<>(ready);
            ready.clear();
            List<CompletableFuture<NodeRunResult>> futures = currentLevel.stream()
                    .map(nodeId -> CompletableFuture.supplyAsync(() -> runDagNode(nodeMap.get(nodeId), incoming.getOrDefault(nodeId, Collections.emptyList()),
                            selfLoopNodeIds.contains(nodeId), outputs, tenantId, userId, sessionId, dagPlan.getWorkflowId(),
                            userMessage, traceId, roleCode, historyCutoffSequence, run)))
                    .collect(Collectors.toList());
            for (CompletableFuture<NodeRunResult> future : futures) {
                NodeRunResult result = joinNodeResult(future);
                outputs.put(result.nodeId(), result.output());
                for (String targetNodeId : outgoing.getOrDefault(result.nodeId(), Collections.emptyList())) {
                    int next = indegree.get(targetNodeId) - 1;
                    indegree.put(targetNodeId, next);
                    if (next == 0) {
                        ready.add(targetNodeId);
                    }
                }
            }
        }

        if (outputs.size() != nodeMap.size()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 执行失败：存在无法满足依赖的节点");
        }
        return terminalOutputs(dagPlan, outgoing, outputs);
    }

    /**
     * 执行单个 DAG 节点；参数是节点、上游、输出、用户、会话、消息和链路ID；返回节点输出。
     */
    private NodeRunResult runDagNode(WorkflowDagPlanEntity.Node node,
                                     List<String> upstreamNodeIds,
                                     boolean selfLoop,
                                     Map<String, String> outputs,
                                     String tenantId,
                                     String userId,
                                     String sessionId,
                                     String workflowId,
                                     String userMessage,
                                     String traceId,
                                     String roleCode,
                                     Integer historyCutoffSequence,
                                     ChatRunEntity run) {
        if (node == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 节点不存在");
        }
        List<String> upstreamOutputs = upstreamNodeIds.stream()
                .map(upstreamNodeId -> "[" + upstreamNodeId + "]\n" + outputs.getOrDefault(upstreamNodeId, ""))
                .collect(Collectors.toList());
        int runTimes = selfLoop ? safeLoopTimes(node.getMaxIterations()) : 1;
        String previousOutput = "";
        for (int index = 1; index <= runTimes; index++) {
            runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
            String prompt = buildDagNodePrompt(userMessage, upstreamOutputs, previousOutput, index, runTimes);
            previousOutput = runDagNodeOnce(node, tenantId, userId, sessionId, workflowId, prompt, traceId, roleCode,
                    historyCutoffSequence, String.join("\n\n", upstreamOutputs), run);
        }
        return new NodeRunResult(node.getNodeId(), previousOutput);
    }

    /**
     * 执行一次节点 Agent；参数是节点、用户、会话、提示词和链路ID；返回模型输出。
     */
    private String runDagNodeOnce(WorkflowDagPlanEntity.Node node, String tenantId, String userId, String sessionId,
                                  String workflowId, String prompt, String traceId, String roleCode,
                                  Integer historyCutoffSequence, String upstreamOutput, ChatRunEntity run) {
        runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
        AiAgentRegisterVO agent = requireAgent(node.getRuntimeAgentId());
        InMemoryRunner runner = agent.getRunner();
        String adkSessionId = invocationSessionId(sessionId + ":" + node.getNodeId());
        ensureAdkSession(runner, agent.getAppName(), userId, adkSessionId);
        Content content = Content.fromParts(Part.fromText(prompt));
        StringBuilder output = new StringBuilder();
        runner.runAsync(userId, adkSessionId, content, RunConfig.builder().build(),
                        runtimeStateDelta(tenantId, userId, sessionId, workflowId, traceId, roleCode,
                                historyCutoffSequence, upstreamOutput, run.getRunId(), run.getCurrentContextRevision()))
                .blockingForEach(event -> {
                    runControlService.requireExecutable(tenantId, userId, run.getRunId(), null);
                    appendContent(output, event.stringifyContent());
                });
        return output.toString();
    }

    /**
     * 构建节点输入提示；参数是用户消息、上游输出、上一轮输出、轮次和总次数；返回提示词。
     */
    private String buildDagNodePrompt(String userMessage, List<String> upstreamOutputs, String previousOutput, int loopIndex, int loopTotal) {
        List<String> lines = new ArrayList<>();
        lines.add("用户本轮输入：");
        lines.add(userMessage == null ? "" : userMessage);
        if (!upstreamOutputs.isEmpty()) {
            lines.add("\n上游节点输出：");
            lines.add(String.join("\n\n", upstreamOutputs));
        }
        if (loopTotal > 1) {
            lines.add("\n循环信息：");
            lines.add("当前是第 " + loopIndex + " / " + loopTotal + " 次执行。");
            if (previousOutput != null && !previousOutput.isBlank()) {
                lines.add("上一轮输出：");
                lines.add(previousOutput);
            }
        }
        lines.add("\n请只完成你这个节点的任务，并输出本节点结果。");
        return String.join("\n", lines);
    }

    /**
     * 读取 DAG 计划；参数是运行时；返回非空 DAG 计划。
     */
    private WorkflowDagPlanEntity requireDagPlan(WorkflowRuntimeEntity runtime) {
        if (runtime == null || runtime.getDagPlan() == null || runtime.getDagPlan().getNodes() == null || runtime.getDagPlan().getNodes().isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工作流 DAG 运行计划不存在");
        }
        return runtime.getDagPlan();
    }

    /**
     * 计算出边表；参数是 DAG 计划；返回节点到后继节点列表。
     */
    private Map<String, List<String>> outgoingEdges(WorkflowDagPlanEntity dagPlan) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        dagPlan.getNodes().forEach(node -> outgoing.put(node.getNodeId(), new ArrayList<>()));
        if (dagPlan.getEdges() == null) {
            return outgoing;
        }
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            if (isSelfLoop(edge) || !outgoing.containsKey(edge.getSourceNodeId()) || !outgoing.containsKey(edge.getTargetNodeId())) {
                continue;
            }
            outgoing.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
        }
        return outgoing;
    }

    /**
     * 计算入边表；参数是 DAG 计划；返回节点到前置节点列表。
     */
    private Map<String, List<String>> incomingEdges(WorkflowDagPlanEntity dagPlan) {
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        dagPlan.getNodes().forEach(node -> incoming.put(node.getNodeId(), new ArrayList<>()));
        if (dagPlan.getEdges() == null) {
            return incoming;
        }
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            if (isSelfLoop(edge) || !incoming.containsKey(edge.getSourceNodeId()) || !incoming.containsKey(edge.getTargetNodeId())) {
                continue;
            }
            incoming.get(edge.getTargetNodeId()).add(edge.getSourceNodeId());
        }
        return incoming;
    }

    /**
     * 计算入度；参数是 DAG 计划；返回节点入度。
     */
    private Map<String, Integer> indegree(WorkflowDagPlanEntity dagPlan) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        dagPlan.getNodes().forEach(node -> indegree.put(node.getNodeId(), 0));
        if (dagPlan.getEdges() == null) {
            return indegree;
        }
        for (WorkflowDagPlanEntity.Edge edge : dagPlan.getEdges()) {
            if (isSelfLoop(edge) || !indegree.containsKey(edge.getSourceNodeId()) || !indegree.containsKey(edge.getTargetNodeId())) {
                continue;
            }
            indegree.put(edge.getTargetNodeId(), indegree.get(edge.getTargetNodeId()) + 1);
        }
        return indegree;
    }

    /**
     * 读取自循环节点；参数是 DAG 计划；返回自循环节点ID集合。
     */
    private Set<String> selfLoopNodeIds(WorkflowDagPlanEntity dagPlan) {
        if (dagPlan.getEdges() == null) {
            return Collections.emptySet();
        }
        return dagPlan.getEdges().stream()
                .filter(this::isSelfLoop)
                .map(WorkflowDagPlanEntity.Edge::getSourceNodeId)
                .collect(Collectors.toSet());
    }

    /**
     * 判断是否自循环边；参数是边；返回是否节点指向自身。
     */
    private boolean isSelfLoop(WorkflowDagPlanEntity.Edge edge) {
        return edge != null && edge.getSourceNodeId() != null && edge.getSourceNodeId().equals(edge.getTargetNodeId());
    }

    /**
     * 拼接终点输出；参数是计划、出边和节点输出；返回最终回复。
     */
    private String terminalOutputs(WorkflowDagPlanEntity dagPlan, Map<String, List<String>> outgoing, Map<String, String> outputs) {
        return terminalNodeIds(dagPlan, outgoing).stream()
                .map(nodeId -> outputs.getOrDefault(nodeId, ""))
                .filter(output -> output != null && !output.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 计算终点节点；参数是计划和出边；返回没有后继节点的节点ID列表。
     */
    private List<String> terminalNodeIds(WorkflowDagPlanEntity dagPlan, Map<String, List<String>> outgoing) {
        List<String> terminalNodeIds = dagPlan.getNodes().stream()
                .map(WorkflowDagPlanEntity.Node::getNodeId)
                .filter(nodeId -> outgoing.getOrDefault(nodeId, Collections.emptyList()).isEmpty())
                .collect(Collectors.toList());
        if (terminalNodeIds.isEmpty()) {
            return List.of(dagPlan.getNodes().get(dagPlan.getNodes().size() - 1).getNodeId());
        }
        return terminalNodeIds;
    }

    /**
     * 等待节点结果；参数是异步任务；返回节点结果。
     */
    private NodeRunResult joinNodeResult(CompletableFuture<NodeRunResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AppException(ResponseCode.UN_ERROR.getCode(), cause.getMessage());
        }
    }

    /**
     * 安全循环次数；参数是候选次数；返回 1 到 20 之间的次数。
     */
    private int safeLoopTimes(Integer maxIterations) {
        if (maxIterations == null || maxIterations < 1) {
            return 1;
        }
        return Math.min(maxIterations, 20);
    }

    /**
     * 获取运行状态；参数是身份、会话、工作流和链路ID；返回传给 ADK 的状态。
     */
    private Map<String, Object> runtimeStateDelta(String tenantId, String userId, String sessionId, String workflowId, String traceId,
                                                  String roleCode, Integer visibleThroughSequence, String upstreamOutput) {
        return runtimeStateDelta(tenantId, userId, sessionId, workflowId, traceId, roleCode,
                visibleThroughSequence, upstreamOutput, null, null);
    }

    /**
     * 获取运行状态；参数包含业务运行和上下文版本；返回传给 ADK 的状态。
     */
    private Map<String, Object> runtimeStateDelta(String tenantId, String userId, String sessionId, String workflowId, String traceId,
                                                  String roleCode, Integer visibleThroughSequence, String upstreamOutput,
                                                  String runId, Long contextRevision) {
        Map<String, Object> state = new HashMap<>();
        putStateIfPresent(state, TraceContext.TRACE_ID_STATE_KEY, traceId);
        putStateIfPresent(state, ToolRuntimeContextKeys.TRACE_ID, traceId);
        putStateIfPresent(state, ToolRuntimeContextKeys.TENANT_ID, tenantId);
        putStateIfPresent(state, ToolRuntimeContextKeys.USER_ID, userId);
        putStateIfPresent(state, ToolRuntimeContextKeys.SESSION_ID, sessionId);
        putStateIfPresent(state, ToolRuntimeContextKeys.WORKFLOW_ID, workflowId);
        putStateIfPresent(state, ToolRuntimeContextKeys.RUN_ID, runId);
        if (contextRevision != null) {
            state.put(ToolRuntimeContextKeys.CONTEXT_REVISION, contextRevision);
        }
        putStateIfPresent(state, "roleCode", roleCode);
        putStateIfPresent(state, ToolRuntimeContextKeys.CONTEXT_UPSTREAM_OUTPUT, upstreamOutput);
        if (visibleThroughSequence != null) {
            state.put(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE, visibleThroughSequence);
        }
        return state;
    }

    /**
     * 写入非空运行状态；参数是状态表、键和值；无返回值。
     */
    private void putStateIfPresent(Map<String, Object> state, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            state.put(key, value);
        }
    }

    /**
     * 计算历史可见切面；参数是本轮用户消息；返回当前输入之前的序号。
     */
    private Integer historyCutoff(ChatMessageEntity userMessage) {
        if (userMessage == null || userMessage.getSequenceNo() == null) {
            return 0;
        }
        return Math.max(0, userMessage.getSequenceNo() - 1);
    }

    /**
     * 创建临时 ADK 会话ID；参数是业务会话ID；返回本轮独立执行会话ID。
     */
    private String invocationSessionId(String businessSessionId) {
        return businessSessionId + ":inv:" + UUID.randomUUID();
    }

    /**
     * 确保会话ID存在；参数是 Agent ID、用户ID和会话ID；返回可用会话ID。
     */
    private String ensureSessionId(String agentId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, userId);
        }
        return sessionId;
    }

    /**
     * 确保会话ID存在；参数是会话 Agent、用户、会话ID和运行体；返回可用会话ID。
     */
    private String ensureSessionId(String sessionAgentId, String userId, String sessionId, AiAgentRegisterVO aiAgentRegisterVO) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(sessionAgentId, userId, aiAgentRegisterVO);
        }
        return sessionId;
    }

    /**
     * 确保会话ID存在；参数是租户、会话 Agent、用户、会话ID和运行体；返回可用会话ID。
     */
    private String ensureSessionId(String tenantId, String sessionAgentId, String userId, String sessionId, AiAgentRegisterVO aiAgentRegisterVO) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(tenantId, sessionAgentId, userId, aiAgentRegisterVO);
        }
        return sessionId;
    }

    /**
     * 获取已注册 Agent；参数是 Agent ID；返回运行体。
     */
    private AiAgentRegisterVO requireAgent(String agentId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }
        return aiAgentRegisterVO;
    }

    /**
     * 确保 ADK 内存会话存在；参数是 Runner、应用、用户和会话ID；无返回值。
     */
    private void ensureAdkSession(InMemoryRunner runner, String appName, String userId, String sessionId) {
        Session session = runner.sessionService()
                .getSession(appName, userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            runner.sessionService()
                    .createSession(appName, userId, new ConcurrentHashMap<>(), sessionId)
                    .blockingGet();
        }
    }

    /**
     * 原子写入运行用户消息；参数是可信身份、运行、内容和链路ID；返回绑定结果。
     */
    private RunMessageBindingEntity saveRunUserMessage(String tenantId, String userId, String runId,
                                                        String content, String traceId) {
        RunMessageBindingEntity binding = runControlService.appendUserMessage(tenantId, userId, runId, content, traceId);
        conversationMemoryService.onMessageSaved(binding.getMessage());
        return binding;
    }

    /**
     * 原子保存助手消息并完成运行；参数是运行身份、内容和链路ID；无返回值。
     */
    private void completeRunWithAssistant(String tenantId, String userId, String runId,
                                          String content, String traceId) {
        ChatMessageEntity message = runControlService.completeWithAssistantMessage(tenantId, userId, runId,
                content, traceId);
        if (message != null) {
            conversationMemoryService.onAssistantMessageSaved(message);
        }
    }

    private void completeRunWithAssistantOnce(AtomicBoolean saved, String tenantId, String userId, String runId,
                                              String content, String traceId) {
        if (saved.compareAndSet(false, true)) {
            completeRunWithAssistant(tenantId, userId, runId, content, traceId);
        }
    }

    /**
     * 原子保存助手错误并终结运行；参数是运行、异常和已生成内容；无返回值。
     */
    private void failRunWithAssistantError(String tenantId, String userId, String runId, String traceId,
                                           Throwable throwable, String partialContent) {
        ChatMessageEntity message = runControlService.failWithAssistantMessage(tenantId, userId, runId,
                errorContent(throwable, partialContent), traceId, safeMessage(throwable));
        if (message != null) {
            conversationMemoryService.onMessageSaved(message);
        }
    }

    private void failRunWithAssistantErrorOnce(AtomicBoolean saved, String tenantId, String userId, String runId,
                                               String traceId, Throwable throwable, String partialContent) {
        if (saved.compareAndSet(false, true)) {
            failRunWithAssistantError(tenantId, userId, runId, traceId, throwable, partialContent);
        }
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "运行失败" : throwable.getClass().getSimpleName();
        }
        String message = throwable.getMessage();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    /**
     * 构造错误内容；参数是异常和已生成内容；返回可落库文本。
     */
    private String errorContent(Throwable throwable, String partialContent) {
        String errorType = throwable == null ? "UnknownError" : throwable.getClass().getSimpleName();
        String errorMessage = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
        String partial = partialContent == null || partialContent.isBlank() ? "" : "\npartialContent=" + partialContent;
        return "[assistant_error] type=" + errorType + " message=" + errorMessage + partial;
    }

    /**
     * 追加流式内容；参数是内容缓冲和分片；无返回值。
     */
    private void appendContent(StringBuilder assistantContent, String content) {
        if (content == null || content.isBlank()) {
            return;
        }

        String current = assistantContent.toString();
        if (content.equals(current)) {
            return;
        }
        if (!current.isBlank() && content.startsWith(current)) {
            assistantContent.append(content.substring(current.length()));
            return;
        }
        assistantContent.append(content);
    }

    /**
     * 描述复合消息；参数是聊天命令；返回可落库文本。
     */
    private String describeContent(ChatCommandEntity chatCommandEntity) {
        List<String> contentList = new ArrayList<>();
        if (chatCommandEntity.getTexts() != null) {
            chatCommandEntity.getTexts().forEach(text -> contentList.add(text.getMessage()));
        }
        if (chatCommandEntity.getFiles() != null) {
            chatCommandEntity.getFiles().forEach(file -> contentList.add("[file] " + file.getFileUri()));
        }
        if (chatCommandEntity.getInlineDatas() != null) {
            chatCommandEntity.getInlineDatas().forEach(inlineData -> contentList.add("[inline_data] " + inlineData.getMimeType()));
        }
        return String.join("\n", contentList);
    }

    /**
     * 组装引导后继输入；参数是运行和请求消息；返回后继运行的完整用户输入。
     */
    private String steerResumeMessage(ChatRunEntity run, String requestMessage) {
        if (run == null || run.getPredecessorRunId() == null || run.getPredecessorRunId().isBlank()) {
            return requestMessage;
        }
        String originalMessage = sessionDomain.queryRunMessages(run.getTenantId(), run.getUserId(), run.getSessionId(),
                        run.getPredecessorRunId()).stream()
                .filter(message -> SessionDomain.ROLE_USER.equals(message.getRole()))
                .map(ChatMessageEntity::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .orElse(requestMessage == null ? "" : requestMessage);
        return originalMessage + "\n\n[用户执行中引导]\n" + run.getSteerInstruction();
    }

    /**
     * DAG 节点执行结果；参数是节点ID和输出；返回不可变结果。
     */
    private record NodeRunResult(String nodeId, String output) {
    }

    /**
     * 获取当前租户ID；无参数；返回租户ID。
     */
    private String currentTenantId() {
        return TenantContextHolder.getTenantId();
    }

}
