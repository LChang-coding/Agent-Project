package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.service.AgentToolPermissionService;
import cn.bugstack.ai.domain.agent.service.ToolApprovalService;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.domain.tool.model.valobj.ToolType;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import cn.bugstack.ai.domain.tool.service.support.SkillPackageReader;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.observability.AiLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型想动真格做事，都必须从这里走：工具调用的唯一执行出口。
 *
 * <p>所属层次：领域层服务，是工具领域的核心。整个系统里只有这个类会真正触发外部副作用，
 * 因此所有的门禁、审计、结果裁剪都集中在这一处，不会散落各地漏掉某条路径。</p>
 *
 * <p>谁调用它：{@code GatewayAdkTool}。模型每决定调用一个工具，ADK 就会执行到那个包装类，
 * 它把模型给的参数和服务端可信身份一起交到这里。</p>
 *
 * <p>它向下调用什么：授权服务（锁运行 + 抢幂等键 + 写审计）、对象存储（取 Skill 包）、
 * Skill 包读取器（在限额内解析 SKILL.md）、MCP 协议客户端（连远程工具服务器），
 * 以及一个仅用于旧式直连 HTTP 的内置客户端。</p>
 *
 * <p>它处理的核心风险：
 * 一是模型给的参数不可信——模型可能漏字段、给错类型、甚至被提示词注入操纵去调危险动作，所以参数必须校验；
 * 二是重复执行——模型重试、网络重发都可能让同一件事来两遍，所以要靠幂等键只放一次通行；
 * 三是失败要能说清楚——失败文案会被带进模型的下一轮提示词，所以只能给可读的业务原因，不能带堆栈和内部细节；
 * 四是结果会撑爆上下文——远程可能返回几十万字，所以统一裁剪长度。</p>
 *
 * <p>它不负责什么：不决定模型能看到哪些工具（在 {@code ToolResolver}）、不生成模型函数 schema
 * （在 {@code GatewayAdkTool}）、不管工具的上架与发版（在 {@code ToolPublishService}）。</p>
 */
@Service
public class ToolGateway {

    /**
     * 返回给大模型的结果文本长度上限。
     *
     * <p>为什么必须裁剪：工具结果会被拼进下一轮提示词。一次返回几十万字，要么直接超出模型窗口报错，
     * 要么把前面的对话历史挤出去，表现为「模型突然忘了刚才说过什么」。
     * 这里选择直接截断尾部，虽然会丢内容，但比整轮对话崩掉可控得多。</p>
     */
    private static final int MAX_RESULT_LENGTH = 16_000;

    /** 对象存储服务；调用 Skill 时凭工具目录里的桶和对象键把已发布的 ZIP 包取回来，只读不写。 */
    private final ObjectStorageService objectStorageService;
    /** 标准 MCP 协议客户端；负责按 SSE 或 stdio 建连、初始化、调用远程工具，并把远程错误翻译成领域异常。 */
    private final McpProtocolClientSupport mcpProtocolClientSupport;
    /**
     * 分发授权服务；本类唯一的门禁依赖。
     *
     * <p>它在一个短事务里完成两件事：给运行加行锁确认没被取消，以及用幂等键抢下唯一执行权。
     * 没有它，用户点了停止工具照样会把外部动作做完，模型重试也会重复产生真实损失。</p>
   */
    private final ToolDispatchAuthorizationService dispatchAuthorizationService;
 /** Skill 包读取器；在条目数、字节数、字符编码三重限额内取出 SKILL.md，挡住压缩炸弹和畸形包。 */
    private final SkillPackageReader skillPackageReader;
    /** 执行由服务端提供的平台内置工具。 */
    private final PlatformToolRegistry platformToolRegistry;
    /** 工作流运行中持久化工具开始、完成和失败事件。 */
    private final WorkflowEventStreamService workflowEventStreamService;
    /** 按 Agent + 工具解析租户级允许、审批或禁止策略。 */
    private final AgentToolPermissionService toolPermissionService;
    /** 为需要人工确认的任意工具创建审批并恢复原调用。 */
    private final ToolApprovalService toolApprovalService;
    /**
     * JSON 工具。只做三件事：把入参序列化成审计文本、把历史输出反序列化用于重放、解析 MCP 参数。
     *
     * <p>无状态可复用；它不承载任何业务规则。</p>
  */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 仅供旧式「直连 HTTP MCP」使用的 HTTP 客户端，连接超时 5 秒。
     *
     * <p>标准的 SSE 和 stdio 都不走它。保留它是为了兼容早期把 MCP 当普通 JSON 接口调用的配置，
     * 新接入请一律使用标准协议。作为成员变量复用是为了共享底层连接池，避免每次调用新建客户端。</p>
     */
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 注入对象存储、MCP 协议客户端、分发授权服务和 Skill 包读取器，完成构造。
   *
     * <p>只做依赖装配，不预热连接也不缓存工具。本类无可变状态，因此线程安全，
     * 并发正确性由数据库行锁和幂等键唯一索引保证。</p>
     */
    public ToolGateway(ObjectStorageService objectStorageService, McpProtocolClientSupport mcpProtocolClientSupport,
                       ToolDispatchAuthorizationService dispatchAuthorizationService,
                       SkillPackageReader skillPackageReader) {
        this(objectStorageService, mcpProtocolClientSupport, dispatchAuthorizationService, skillPackageReader,
                new PlatformToolRegistry(), null, null, null);
    }

    /**
     * 创建支持平台内置工具的网关，主要供不需要工作流事件的测试和兼容装配使用。
     *
     * @param objectStorageService Skill 内容所在的对象存储服务
     * @param mcpProtocolClientSupport MCP 协议调用支持
     * @param dispatchAuthorizationService 工具调用授权与幂等登记服务
     * @param skillPackageReader 受限读取 Skill 包内容的组件
     * @param platformToolRegistry 平台内置工具注册表
     */
    public ToolGateway(ObjectStorageService objectStorageService, McpProtocolClientSupport mcpProtocolClientSupport,
                       ToolDispatchAuthorizationService dispatchAuthorizationService,
                       SkillPackageReader skillPackageReader, PlatformToolRegistry platformToolRegistry) {
        this(objectStorageService, mcpProtocolClientSupport, dispatchAuthorizationService, skillPackageReader,
                platformToolRegistry, null, null, null);
    }

    /**
     * 创建完整工具网关。
     *
     * @param objectStorageService Skill 内容所在的对象存储服务
     * @param mcpProtocolClientSupport MCP 协议调用支持
     * @param dispatchAuthorizationService 工具调用授权与幂等登记服务
     * @param skillPackageReader 受限读取 Skill 包内容的组件
     * @param platformToolRegistry 平台内置工具注册表
     * @param workflowEventStreamService 工作流工具事件发布服务；非工作流装配时可以为空
     */
    public ToolGateway(ObjectStorageService objectStorageService, McpProtocolClientSupport mcpProtocolClientSupport,
                       ToolDispatchAuthorizationService dispatchAuthorizationService,
                       SkillPackageReader skillPackageReader, PlatformToolRegistry platformToolRegistry,
                       WorkflowEventStreamService workflowEventStreamService) {
        this(objectStorageService, mcpProtocolClientSupport, dispatchAuthorizationService, skillPackageReader,
                platformToolRegistry, workflowEventStreamService, null, null);
    }

    /** 生产环境完整网关；所有 Skill、MCP 和平台工具都在统一门禁中应用策略。 */
    @Autowired
    public ToolGateway(ObjectStorageService objectStorageService, McpProtocolClientSupport mcpProtocolClientSupport,
                       ToolDispatchAuthorizationService dispatchAuthorizationService,
                       SkillPackageReader skillPackageReader, PlatformToolRegistry platformToolRegistry,
                       WorkflowEventStreamService workflowEventStreamService,
                       AgentToolPermissionService toolPermissionService, ToolApprovalService toolApprovalService) {
        this.objectStorageService = objectStorageService;
        this.mcpProtocolClientSupport = mcpProtocolClientSupport;
        this.dispatchAuthorizationService = dispatchAuthorizationService;
        this.skillPackageReader = skillPackageReader;
        this.platformToolRegistry = platformToolRegistry;
        this.workflowEventStreamService = workflowEventStreamService;
        this.toolPermissionService = toolPermissionService;
        this.toolApprovalService = toolApprovalService;
    }

  /**
     * 执行一次工具调用：先过门禁，再真正干活，最后把结果闭环写进审计。
     *
     * <p>各层职责：
     * 第一层：校验工具标识和可信身份。身份不全就在产生任何副作用之前失败关闭，
     *      因为后面的租户隔离、幂等键和审计归属全都依赖它。
     * 第二层：向授权服务领执行权。这一步会锁运行、确认没被取消、并用幂等键抢下唯一通行证。
     *         没抢到就说明这件事已经有人做过，直接重放历史结果，绝不重复产生外部消耗。
     * 第三层：按工具类型路由到 Skill 或 MCP 运行时，这里是全流程中唯一真正执行工具的地方。
   * 第四层：成功则回填成功审计并把结果返回给模型。
  * 第五层：失败则把异常转成模型能读的错误文案，同时尽力把 started 审计推进成失败态。
     *   注意异常在这里被吃掉、以返回值形式交还模型——这是有意的，模型需要看到失败原因才能换个参数重试，
     *    而不是让整轮对话直接崩掉。</p>
     *
     * <p>数据流：
  * 工具目录项 + 模型给的参数 + 可信上下文
     * → 身份与工具校验
     * → 入参序列化成审计文本
     * → 领取执行权（锁运行 + 幂等键）
     * → 未领到则重放历史结果并结束
     * → 领到则按类型路由执行（外部副作用在此发生）
     * → 成功：回填 success 审计 → 返回结果给模型
     * → 失败：回填 failed 审计 → 返回错误文案给模型</p>
     *
     * <p>返回结构固定含 success 标志：成功时带 result 文本，失败时带 error 文案，重放时额外带 replayed 标记。
     * 会写库（审计）、会加行锁、会调用外部系统。</p>
  */
    public Map<String, Object> invoke(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context) {
     // 第一层：门禁之前的门禁——工具标识和身份任一缺失就直接抛异常，绝不带着不完整信息去碰外部系统。
        checkInvoke(tool, context);
     // 记录整体开始时间，用于统计包含门禁在内的端到端耗时。
        long begin = System.currentTimeMillis();
   // 把模型给的参数序列化成审计文本；序列化失败会退化成空对象，不会因为记日志失败而阻断调用。
        Map<String, Object> effectiveInput = authorizeByConfiguredPolicy(tool, input, context);
        String inputJson = toJson(effectiveInput);
    // 单独记一次门禁阶段的开始时间，好把「等锁」的耗时和「工具执行」的耗时区分开。
        long claimStarted = System.currentTimeMillis();
        // 打一条门禁开始日志：工具调用是最容易出问题的一环，分阶段留痕才能定位到底卡在哪一步。
        AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                context.getTraceId(), "dispatch_authorize", "开始校验运行状态并领取幂等调用权",
                "started", 0L));
    // 门禁结果：是否领到执行权，以及对应的审计日志。
        ToolDispatchClaimEntity claim;
     // 领取过程可能因运行已取消、上下文过期等原因抛异常，需要先记日志再原样抛出。
        try {
            // 向授权服务领执行权：内部会锁运行、校验上下文版本，并用幂等键抢下唯一通行证。
            claim = dispatchAuthorizationService.claim(tool, context, inputJson);
        } catch (RuntimeException exception) {
    // 门禁失败意味着工具一次都没被调用，这一点必须在日志里写清楚，否则排查时会误以为外部动作已经发生。
            AiLog.warn(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                    context.getTraceId(), "dispatch_authorize",
                    "运行状态校验或幂等调用权领取失败，未调用外部工具", "failed",
                    System.currentTimeMillis() - claimStarted)
                    .field("errorType", exception.getClass().getSimpleName()));
      // 原样抛出而不是转成返回值：门禁失败属于流程性错误（运行已取消、上下文过期），应由上层统一处理。
            throw exception;
        }
        // 第二层：没领到执行权说明这次调用此前已经处理过，只重放历史结果，绝不再执行一遍。
        if (!claim.isClaimed()) {
     // 重试只重放持久化结果，started 未知态也绝不二次执行。
            AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                    context.getTraceId(), "dispatch_authorize", "命中既有工具调用，不重复产生外部消耗",
                    "replayed", System.currentTimeMillis() - claimStarted));
    // 按历史记录的状态决定重放什么内容。
            return duplicateResult(claim.getCallLog());
        }
        // 领到执行权，记一条门禁通过日志，含等锁耗时。
        AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                context.getTraceId(), "dispatch_authorize", "运行状态校验和幂等调用权领取完成",
                "completed", System.currentTimeMillis() - claimStarted));
   // 留住这条 started 审计，稍后无论成功还是失败都要凭它回填终态。
        ToolCallLogEntity callLog = claim.getCallLog();
        publishToolEvent(context, tool, "TOOL_CALL_STARTED", Map.of());
   // 打一条「工具开始执行」的日志，从这一刻起外部副作用随时可能发生。
        AiLog.info(AiLog.tool().callStarted(context.getTenantId(), context.getUserId(), context.getSessionId(),
                context.getRunId(),
                tool.getToolType(), tool.getToolId(), tool.getToolName(), context.getTraceId()));
        // 工具执行随时可能失败，但无论如何都必须给模型一个能读懂的结构化答复。
        try {
        // 记录路由结论：Skill 走读包路径，MCP 走协议路径，两者的失败原因完全不同，先写下来便于排查。
            AiLog.info(AiLog.tool().stage(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(), tool.getToolType(), tool.getToolId(), tool.getToolName(),
                    context.getTraceId(), "runtime_route",
                    runtimeName(tool.getToolType()),
                    "completed", 0L));
            // 此处是通过授权和幂等门禁后唯一的真实工具执行点。
            ToolExecutionResult execution = dispatch(tool, effectiveInput, context);
   // 算出端到端耗时，写进审计供发现慢工具。
            long costMs = System.currentTimeMillis() - begin;
   // 第四层：把结果闭环写进审计。审计成功后这次调用才真正「有据可查」，重试也能安全重放它。
            dispatchAuthorizationService.finish(callLog, execution.auditJson(objectMapper),
                    ToolStatus.SUCCESS, null, null, costMs);
            publishToolEvent(context, tool, "TOOL_CALL_COMPLETED", completedPayload(execution, costMs));
    // 记一条成功日志，含耗时。
            AiLog.info(AiLog.tool().callSuccess(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(),
                    tool.getToolType(), tool.getToolId(), tool.getToolName(), context.getTraceId(), costMs));
   // 返回固定结构给模型：success 标志 + 结果文本，模型据此继续推理。
            return Map.of("success", true, "result", execution.modelResult());
        } catch (Exception e) {
        // 工具异常转换为模型结果，并尽力将 started 审计推进为 failed。
            long costMs = System.currentTimeMillis() - begin;
   // 先尽力把审计推进成失败态；这一步自身失败也不能影响下面返回给模型的内容。
            finishFailedSafely(callLog, e, costMs);
            publishToolEvent(context, tool, "TOOL_CALL_FAILED", failedPayload(e, costMs));
        // 完整异常（含堆栈）只写日志，不返回给模型，避免内部实现细节进入提示词。
            AiLog.error(AiLog.tool().callFailed(context.getTenantId(), context.getUserId(), context.getSessionId(),
                    context.getRunId(),
                    tool.getToolType(), tool.getToolId(), tool.getToolName(), context.getTraceId(), costMs, e));
   // 把失败转成结构化返回而不是继续抛：模型需要看到一句可读的原因，才能换参数重试或换个说法回答用户。
            return Map.of("success", false, "error", safeMessage(e));
        }
    }

    /** 在幂等领取和外部副作用之前，对所有工具应用同一套 Agent 权限策略。 */
    private Map<String, Object> authorizeByConfiguredPolicy(ToolCatalogEntity tool, Map<String, Object> input,
                                                             ToolInvokeContextEntity context) {
        Map<String, Object> original = input == null ? Map.of() : input;
        if (toolPermissionService == null || toolApprovalService == null || context == null
                || context.getAgentId() == null || context.getAgentId().isBlank()) return original;
        String toolCode = AgentToolPermissionService.permissionCode(tool);
        AgentToolPermissionEntity policy = toolPermissionService.resolve(context.getTenantId(), context.getAgentId(), toolCode);
        if ("DENY".equals(policy.getMode())) {
            throw new AppException("AGENT_TOOL_PERMISSION_DENIED", "当前 Agent 已禁止调用该工具");
        }
        if (!"REQUIRE_APPROVAL".equals(policy.getMode())) return original;
        ToolApprovalRequestEntity request = toolApprovalService.request(context, toolCode, original, policy);
        ToolApprovalRequestEntity decision = toolApprovalService.awaitDecision(request, context);
        if ("APPROVE".equals(decision.getDecision())) return original;
        if ("APPROVE_WITH_CHANGES".equals(decision.getDecision()) && decision.getAmendedInput() != null) {
            return decision.getAmendedInput();
        }
        if ("REPLAN".equals(decision.getDecision())) {
            throw new AppException("TOOL_APPROVAL_REPLAN_REQUIRED", "请根据审批意见重新规划工具调用");
        }
        throw new AppException("TOOL_APPROVAL_REJECTED", "本次工具调用已被拒绝");
    }

    /**
     * 这次调用此前已经处理过时，决定该把什么结果重放给模型。
     *
     * <p>各层职责：
     * 第一层：历史记录是成功且有输出，就把当时的结果原样取出来重放。
 * 第二层：历史记录是失败，就把当时的失败原因重放，让模型知道这条路走不通。
     * 第三层：历史记录还停在 started，说明上一次执行的结果无法确定——可能成功了只是没记上。
   *         这种情况下明确告诉模型「结果未知，不再执行」，宁可让模型少一次信息，也不能冒重复扣费的风险。</p>
     *
     * <p>数据流：历史审计记录 → 判断状态 → 成功则解析输出 JSON 取 result → 失败则取错误文案
     * → started 则给出未知态说明 → 统一带上 replayed 标记返回。</p>
     *
     * <p>不写库、不产生外部副作用。返回里的 replayed 标记很有用：模型和排查人员据此知道这不是一次新执行。
     * 历史输出解析失败时也只返回一句说明，不抛异常——毕竟这次调用本身并没有出错。</p>
     */
    private Map<String, Object> duplicateResult(ToolCallLogEntity log) {
   // 第一层：成功且有输出，才有可能重放出有意义的内容。
        if (ToolStatus.SUCCESS.equals(log.getStatus()) && log.getOutputJson() != null) {
     // 历史 JSON 可能因为版本变化解析不了，需要接住。
            try {
    // 把当时保存的输出解回来。
                Map<String, Object> output = objectMapper.readValue(log.getOutputJson(), new TypeReference<>() {
                });
    // 取出 result 字段重放；缺字段时给空串，保持返回结构稳定。
                Object result = output.containsKey("modelResult") ? output.get("modelResult") : output.getOrDefault("result", "");
                return Map.of("success", true, "result", result,
                        "replayed", true);
            } catch (Exception ignored) {
   // 解析不了就诚实告知模型，而不是抛异常——本次调用其实没有失败，只是拿不到旧结果。
                return Map.of("success", false, "error", "工具调用已完成，但历史结果无法解析");
            }
        }
        // 第二层：历史是失败，把当时的原因重放，模型据此不要在同一条路上反复试。
        if (ToolStatus.FAILED.equals(log.getStatus())) {
      // 错误文案可能没存下来，用一句兜底说明代替空值。
            return Map.of("success", false, "error", defaultString(log.getErrorMessage(), "工具调用此前已失败"),
                    "replayed", true);
        }
        // 第三层：还停在 started，说明上一次是否已经产生外部副作用无法判断。
     // 这种未知态一律拒绝再执行——重复下单、重复扣费的代价远大于让模型少拿一次结果。
        return Map.of("success", false, "error", "工具调用已开始，当前结果未知；为避免重复消耗不再执行",
                "replayed", true);
    }

  /**
 * 尽力把审计推进成失败态，但绝不让审计本身的异常盖掉工具原本的错误。
     *
     * <p>为什么要单独包一层：如果在异常处理里再抛异常，调用方看到的就是「审计更新失败」，
   * 而工具真正的失败原因（比如远程返回参数不合法）就彻底丢了，排查时完全找不到方向。</p>
     *
     * <p>数据流：原始异常 → 尝试写失败审计 → 写成功则结束 → 写失败则单独记一条二次错误日志。</p>
     *
     * <p>会写库。二次失败只记日志不再上抛，代价是这条记录可能长期停在 started，
   * 后续重试会拿到「结果未知」——这是可接受的保守结果。</p>
     */
    private void finishFailedSafely(ToolCallLogEntity log, Exception error, long costMs) {
        // 审计更新自身也可能失败（数据库抖动、连接断开），必须接住。
        try {
       // 写入失败状态、异常类型短码和裁剪后的错误摘要，供事后统计失败原因分布。
            dispatchAuthorizationService.finish(log, null, ToolStatus.FAILED,
                    error.getClass().getSimpleName(), safeMessage(error), costMs);
        } catch (Exception auditError) {
     // 只记这条二次错误，不上抛：保住工具原始异常的可见性比保住审计完整性更重要。
            AiLog.error(AiLog.tool().callFailed(log.getTenantId(), log.getUserId(), log.getSessionId(),
                    log.getRunId(),
                    log.getToolType(), log.getToolId(), log.getToolName(), log.getTraceId(), costMs, auditError));
        }
    }

    /**
     * 按工具类型把调用路由到对应的运行时，这是工具分发的岔路口。
     *
 * <p>Skill 走「读已发布包、返回指令文本」的路径；MCP 走「连远程服务器、执行远程工具」的路径。
     * 两者语义差别很大：前者不产生外部副作用，后者会。</p>
     *
     * <p>类型不认识时直接抛异常而不是默认走某一条：默认猜测会让一个配置错误变成一次不该发生的外部调用。</p>
 */
    private ToolExecutionResult dispatch(ToolCatalogEntity tool, Map<String, Object> input, ToolInvokeContextEntity context) {
        // Skill 类型：读包取指令文本。
        if (ToolType.SKILL.equals(tool.getToolType())) {
            // 交给 Skill 路径：读出已发布包里的说明书文本，不执行包内任何代码。
            return ToolExecutionResult.standard(invokeSkill(tool, input));
        }
        // MCP 类型：连远程服务器真正执行动作。
        if (ToolType.MCP.equals(tool.getToolType())) {
            // 交给 MCP 路径：真的连上外部服务器执行动作，会产生不可撤销的副作用。
            return ToolExecutionResult.standard(invokeMcp(tool, input));
        }
        if (ToolType.PLATFORM.equals(tool.getToolType())) {
            requirePlatformContext(context);
            PlatformToolResult result = platformToolRegistry.dispatch(tool, input, context);
            if (!result.success()) throw new AppException(platformErrorCode(result.error()), platformErrorMessage(result.error()));
            return ToolExecutionResult.platform(result.modelResult(), result.auditResult());
        }
 // 既不是 Skill 也不是 MCP，说明数据异常；失败关闭，绝不猜测。
        throw new AppException("TOOL_TYPE_UNSUPPORTED", "工具类型不支持：" + tool.getToolType());
    }

    /** 将工具类型映射为可观测日志中的运行时名称。 */
    private String runtimeName(String type) {
        if (ToolType.SKILL.equals(type)) return "路由到Skill运行时";
        if (ToolType.MCP.equals(type)) return "路由到MCP运行时";
        return "路由到平台工具运行时";
    }

    /** 平台工具必须绑定权威运行和函数调用身份，缺失时禁止执行副作用。 */
    private void requirePlatformContext(ToolInvokeContextEntity context) {
        if (blank(context.getRunId()) || blank(context.getFunctionCallId())) {
            throw new AppException("PLATFORM_TOOL_CONTEXT_INVALID", "平台工具缺少运行或函数调用上下文");
        }
    }

    /** 所有 Agent 运行都发布工具事件；工作流额外携带节点执行编号。 */
    private void publishToolEvent(ToolInvokeContextEntity context, ToolCatalogEntity tool, String eventType,
                                  Map<String, Object> details) {
        if (workflowEventStreamService == null || context == null || blank(context.getRunId())
                || blank(context.getTraceId())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("toolCode", defaultString(tool.getToolCode(), defaultString(tool.getFunctionName(), tool.getToolId())));
        payload.put("displayName", defaultString(tool.getToolName(), tool.getToolId()));
        payload.put("functionCallId", context.getFunctionCallId());
        if (!blank(context.getNodeExecutionId())) payload.put("nodeExecutionId", context.getNodeExecutionId());
        workflowEventStreamService.publish(context.getTenantId(), context.getUserId(), context.getRunId(),
                context.getTraceId(), eventType, context.getNodeExecutionId(), null, toJson(payload));
        String rootRunId = context.getOrchestrationRootRunId();
        if (AgentOrchestrationContextHolder.isSummaryOnly()
                && !blank(rootRunId) && !rootRunId.equals(context.getRunId())) {
            payload.put("sourceRunId", context.getRunId());
            workflowEventStreamService.publish(context.getTenantId(), context.getUserId(), rootRunId,
                    context.getTraceId(), eventType, context.getNodeExecutionId(), null, toJson(payload));
        }
    }

    /** 构造成功事件审计负载，不把模型展示正文混入结构化字段。 */
    private Map<String, Object> completedPayload(ToolExecutionResult execution, long costMs) {
        Map<String, Object> payload = new LinkedHashMap<>(execution.eventAuditResult());
        payload.put("success", true);
        payload.put("costMs", costMs);
        return payload;
    }

    /** 构造失败事件负载，只暴露稳定错误码、重试属性和耗时。 */
    private Map<String, Object> failedPayload(Exception error, long costMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", error instanceof AppException appException
                ? appException.getCode() : error.getClass().getSimpleName());
        payload.put("retryable", false);
        payload.put("costMs", costMs);
        return payload;
    }

    /** 从平台工具错误串提取合法稳定错误码，格式不符时使用统一代码。 */
    private String platformErrorCode(String error) {
        if (error == null) return "PLATFORM_TOOL_FAILED";
        int separator = error.indexOf(':');
        String code = separator < 0 ? error : error.substring(0, separator);
        return code.matches("[A-Z][A-Z0-9_]*") ? code : "PLATFORM_TOOL_FAILED";
    }

    /** 从平台工具错误串提取可展示消息，缺失时返回统一说明。 */
    private String platformErrorMessage(String error) {
        if (error == null || error.isBlank()) return "平台工具调用失败";
        int separator = error.indexOf(':');
        if (separator < 0 || separator == error.length() - 1) return error;
        return error.substring(separator + 1);
    }

    /**
     * 执行一个 Skill：把已发布包里的 SKILL.md 取出来，拼上名称、版本和本次任务，返回给模型。
     *
     * <p>数据流：工具目录项 → 从对象存储取 ZIP → 在限额内解析出 SKILL.md
     * → 取出模型给的任务描述 → 拼装成「名称 + 版本 + 任务 + 说明书」文本 → 裁剪长度 → 返回。</p>
     *
     * <p>关键语义：这里绝不执行包里的任何代码。Skill 的定位是「给模型看的说明书」，
     * 调用它的效果是把说明书内容送进模型的上下文，让模型照着做，而不是让服务器去跑用户上传的程序。
     * 这条边界让 Skill 天然不具备任意代码执行的风险。</p>
  *
     * <p>为什么把名称和版本也拼进去：模型能明确知道自己正在按哪个工具的哪一版说明书办事，
     * 也让人工排查对话记录时能一眼看出用的是哪一版。</p>
     *
     * <p>任务描述是可选的：模型没给也照样返回说明书，因为很多时候模型只是想先读一遍再决定怎么做。</p>
     */
    private String invokeSkill(ToolCatalogEntity tool, Map<String, Object> input) {
        // 从对象存储取回已发布版本的原始 ZIP；桶和对象键来自目录项，是发布时冻结的，不受草稿改动影响。
        byte[] bytes = objectStorageService.getObject(tool.getBucket(), tool.getObjectKey());
        // 在条目数、字节数、编码三重限额内解析出说明书文本，挡住压缩炸弹和畸形包。
        String skillMd = skillPackageReader.readSkillMd(bytes);
        // 取出模型给的任务描述；它是模型可控的自由文本，只用于拼进返回内容，不参与任何路径或命令拼接。
        String task = stringValue(input.get("task"));
   // 用缓冲区拼装返回文本，避免多段字符串反复拼接。
        StringBuilder result = new StringBuilder();
        // 先写工具名称，让模型明确自己正在用哪个 Skill。
        result.append("Skill 名称：").append(tool.getToolName()).append('\n');
    // 再写版本号，保证模型和排查人员都知道这是哪一版说明书。
        result.append("Skill 版本：").append(tool.getVersion()).append('\n');
      // 模型给了任务描述才写进去，并空一行与说明书正文分隔，便于模型区分「我的任务」和「说明书内容」。
        if (task != null && !task.isBlank()) {
            // 写入模型给的任务描述，并空一行与说明书正文分隔，便于模型区分两者。
            result.append("本次任务：").append(task).append("\n\n");
        }
     // 最后附上说明书正文，这是这次调用真正的价值所在。
        result.append(skillMd);
 // 按上限裁剪后返回：说明书可能很长，超长会把对话历史挤出模型上下文。
        return truncate(result.toString());
    }

  /**
     * 执行一个 MCP 工具，这是本类真正产生外部副作用的地方。
     *
   * <p>各层职责：
 * 第一层：确认传输类型在支持范围内。不在范围（例如 local）就直接失败，绝不尝试连接。
     * 第二层：除 stdio 外都必须有地址，缺地址直接失败。
     * 第三层：SSE 和 stdio 走标准协议——先解析出远程工具名和参数，再交给协议客户端执行。
  * 第四层：http 走旧式兼容路径，把入参整体当 JSON POST 出去，并按状态码判断成败。</p>
     *
     * <p>数据流：
     * 工具目录项 + 模型给的参数
     * → 传输类型规范化与白名单校验
     * → 地址必填校验
     * → 标准协议分支：解析工具名与参数 → 协议客户端建连并调用 → 返回结果
     * → 旧式 HTTP 分支：构造 15 秒超时的 POST → 判断状态码 → 裁剪响应体返回</p>
     *
     * <p>超时策略：旧式 HTTP 分支显式设 15 秒请求超时（外加客户端 5 秒连接超时）；
     * 标准协议分支的超时由协议客户端按配置或 30 秒默认值控制。没有超时的外部调用会把线程永久占住，
     * 并发一多就拖垮整个对话服务。</p>
  *
     * <p>重试策略：这一层不做任何自动重试。原因是工具调用大多不可幂等（重发可能重复下单），
  * 真正的「重试」交给大模型下一轮决定，而幂等键会保证同一次函数调用不会被执行两遍。</p>
     *
     * <p>状态码大于等于 400 一律视为失败：把错误响应体当成正常结果返回给模型，
     * 会让审计记成成功，事后完全查不出问题。</p>
     */
    private String invokeMcp(ToolCatalogEntity tool, Map<String, Object> input) {
// 传输类型统一转小写，兼容配置里各种大小写写法；为空时用空串，后面必然被白名单拦下。
        String transportType = tool.getTransportType() == null ? "" : tool.getTransportType().toLowerCase();
   // 第一层：白名单校验。只放行这三种，其余（例如 local）明确拒绝，不做任何猜测性的连接尝试。
        if (!"http".equals(transportType) && !"sse".equals(transportType) && !"stdio".equals(transportType)) {
            // 明确拒绝未接入的传输类型，绝不做任何猜测性的连接尝试。
            throw new AppException("TOOL_MCP_LOCAL_DISABLED", "local MCP 当前未接入 ToolGateway");
        }
        // 第二层：除 stdio（靠命令启动子进程）之外都必须有地址，否则不知道该连去哪里。
        if (!"stdio".equals(transportType) && (tool.getEndpoint() == null || tool.getEndpoint().isBlank())) {
            // 没有地址就无从确定连去哪里，直接失败而不是使用某个默认地址。
            throw new AppException("TOOL_MCP_ENDPOINT_EMPTY", "MCP endpoint 不能为空");
        }
   // 第三层：标准协议路径。
        if ("sse".equals(transportType) || "stdio".equals(transportType)) {
          // 标准协议调用前解析并校验远程具体工具名。
            McpCallCommand command = parseMcpCallCommand(tool, input);
       // 交给协议客户端建连、初始化、调用，并由它负责错误翻译与结果裁剪。
            return mcpProtocolClientSupport.callTool(tool, command.toolName(), command.arguments());
        }
        // 第四层：旧式直连 HTTP 兼容路径，外部请求随时可能抛异常，需要接住并翻译。
        try {
        // 把模型给的参数整体当作 JSON 请求体 POST 出去，并显式设置 15 秒请求超时，避免线程被卡死。
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tool.getEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(input), StandardCharsets.UTF_8))
                    .build();
      // 同步发送并按 UTF-8 读取响应体，避免中文乱码进入模型上下文。
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
     // 4xx/5xx 一律视为失败：把错误响应当成正常结果返回会让审计记成成功，事后无法追查。
            if (response.statusCode() >= 400) {
                // 把状态码带进错误文案，便于区分是鉴权失败还是远程自身报错。
                throw new AppException("TOOL_MCP_CALL_FAILED", "MCP 返回状态码：" + response.statusCode());
            }
     // 成功时裁剪响应体后返回，防止超长响应撑爆模型上下文。
            return truncate(response.body());
        } catch (AppException e) {
  // 上面主动抛出的业务异常已经带了准确的错误码，原样上抛，不要被下面的兜底覆盖。
            throw e;
        } catch (Exception e) {
      // 网络层意外故障（连接被拒、超时、DNS 失败等）统一收敛成一个错误码，只带一句可读原因。
            throw new AppException("TOOL_MCP_CALL_FAILED", "MCP 调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从模型给的参数里解析出「要调远程哪个工具」和「用什么参数调」。
     *
     * <p>这是最需要防守的一段：工具名和参数全部来自大模型，它可能用不同字段名、可能把工具名混在参数里、
     * 也可能干脆不给。校验不严的后果是拿着空名字或错名字去调远程，得到一堆含糊错误。</p>
     *
     * <p>各层职责：
     * 第一层：先把参数解析出来（支持对象或 JSON 文本两种形态）。
  * 第二层：按优先级从多个可能的字段里找工具名——顶层的 toolName、mcpToolName、name，
     *      以及参数里混进来的同名字段。兼容这么多写法是因为不同模型的输出习惯确实不同。
     * 第三层：模型没给名字时，如果这个 MCP 只有一个远程工具，就推断成它。
  * 第四层：还是拿不到名字就失败，并把可用工具清单一起告诉模型，让它下一轮能给对。</p>
     *
     * <p>数据流：模型参数 → 解析参数表 → 从多个候选字段取工具名（同时把路由字段从参数里摘掉）
     * → 为空则尝试单工具推断 → 仍为空则带可用清单报错 → 返回工具名 + 纯业务参数。</p>
     *
     * <p>为什么要把路由字段从参数里摘掉：toolName 这类字段是给我们路由用的，不是远程工具的业务参数。
     * 忘了摘掉会把它一起发给远程，可能触发远程的参数校验失败。</p>
     */
    private McpCallCommand parseMcpCallCommand(ToolCatalogEntity tool, Map<String, Object> input) {
        // 第一层：先把业务参数解析出来，这一步同时也会把混在参数里的路由字段准备好被摘除。
        Map<String, Object> arguments = parseMcpArguments(input);
        // 第二层：按优先级找工具名。顶层字段优先于参数内字段；取值时顺带把参数里的路由字段移除，避免误发给远程。
        String toolName = firstText(input.get("toolName"), input.get("mcpToolName"), input.get("name"),
                arguments.remove("toolName"), arguments.remove("mcpToolName"), arguments.remove("_toolName"));
        // 第三层：模型没给名字时，只有在「远程确实只有一个工具」的情况下才敢推断，多个工具时绝不猜。
        if (toolName == null || toolName.isBlank()) {
            // 尝试按「远程只有一个工具」推断名字；推不出会保持为空，由下一步明确报错。
            toolName = inferSingleToolName(tool.getSchemaJson());
        }
        // 第四层：仍然拿不到名字就失败，同时把可用工具清单带进错误文案。
        if (toolName == null || toolName.isBlank()) {
      // 取出这个 MCP 已发布的远程工具名列表。
            List<String> toolNames = mcpProtocolClientSupport.toolNames(tool.getSchemaJson());
      // 错误文案会进入模型的下一轮提示词，所以把可选项列出来，模型下一轮就能给出正确名字。
            throw new AppException("TOOL_MCP_TOOL_NAME_EMPTY", "MCP 调用缺少 toolName，可用工具：" + String.join(",", toolNames));
        }
        // 返回路由结论：远程工具名 + 已剔除路由字段的纯业务参数。
        return new McpCallCommand(toolName, arguments);
    }

    /**
     * 把模型给的参数整理成要发给远程工具的参数表。
   *
     * <p>各层职责：
     * 第一层：argumentsJson 已经是对象时直接规范化键类型。
 * 第二层：argumentsJson 是 JSON 文本时解析它；解析不了就失败，绝不把一段坏文本原样发给远程。
     * 第三层：模型完全没给 argumentsJson 时，退化成「把顶层参数当业务参数」，但要先剔除路由字段。</p>
     *
     * <p>数据流：模型参数 → 取 argumentsJson → 是对象则规范化键 → 是文本则解析 JSON
     * → 都没有则复制顶层参数并移除 toolName/mcpToolName/name → 返回参数表。</p>
     *
     * <p>为什么要兼容这么多形态：我们给模型声明的 schema 里 argumentsJson 是字符串类型，
     * 但模型经常自作主张直接给一个 JSON 对象，或者干脆把业务参数平铺在顶层。
     * 严格只接受一种形态会导致大量调用白白失败，所以这里在不牺牲安全的前提下尽量兼容。</p>
     *
     * <p>解析失败必须抛异常而不是当成空参数：空参数调用远程工具可能触发一个语义完全不同的动作。</p>
     */
    private Map<String, Object> parseMcpArguments(Map<String, Object> input) {
        // 取出模型给的参数载荷，它可能是对象、JSON 文本，也可能压根没有。
        Object argumentsJson = input.get("argumentsJson");
      // 第一层：已经是 Map 说明模型直接给了对象，只需把键统一成字符串即可使用。
        if (argumentsJson instanceof Map<?, ?> map) {
            // 键统一成字符串后直接当作业务参数使用，不再做二次解析。
            return normalizeMap(map);
        }
        // 第二层：非空且非空白说明模型给了一段 JSON 文本，需要解析。
        if (argumentsJson != null && !String.valueOf(argumentsJson).isBlank()) {
       // 模型给的文本很可能不是合法 JSON，必须接住解析异常。
            try {
   // 解析成参数表；这里不做字段级校验，具体参数是否合法由远程工具自己判断。
                return objectMapper.readValue(String.valueOf(argumentsJson), new TypeReference<>() {
                });
            } catch (Exception e) {
     // 解析失败必须失败关闭：把坏文本当空参数发出去，可能触发一个语义完全不同的远程动作。
                throw new AppException("TOOL_MCP_ARGUMENTS_INVALID", "argumentsJson 必须是合法 JSON 对象");
            }
        }
        // 第三层：模型没给参数载荷时，退化成把顶层参数当业务参数用。
        Map<String, Object> arguments = new LinkedHashMap<>(input);
        // 剔除路由字段，它们是给我们选工具用的，不是远程工具的业务参数，发过去可能被远程判为非法参数。
        arguments.remove("toolName");
        // 同时剔除别名写法，避免模型用了别名时漏摘。
        arguments.remove("mcpToolName");
        // 剔除最容易和业务字段撞名的 name，防止它被当成远程参数发出去。
        arguments.remove("name");
        // 返回剔除后的纯业务参数。
        return arguments;
    }

    /**
     * 把任意键类型的 Map 统一成字符串键的 Map。
     *
     * <p>模型给的对象经 JSON 解析后键本应是字符串，但类型系统上是通配的，
  * 直接强制转换会抛类型异常。这里逐项转换，既安全又保持原有顺序（顺序对人工排查有帮助）。</p>
     */
    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        // 用保序表承接，保持模型给出的字段顺序，便于比对日志和实际请求。
        Map<String, Object> result = new LinkedHashMap<>();
        // 逐项把键转成字符串后放入；值原样保留，交给远程工具自己解释。
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        // 返回规范化后的参数表。
        return result;
    }

    /**
     * 当这个 MCP 只有一个远程工具时，替模型把工具名推断出来。
   *
     * <p>为什么只在「恰好一个」时推断：一个的时候答案是唯一的，推断不会出错；
   * 有多个时任何猜测都可能触发一个用户根本没打算执行的外部动作，所以宁可报错让模型明确指定。</p>
     *
     * <p>返回 null 表示无法推断，调用方会带着可用清单报错。</p>
     */
    private String inferSingleToolName(String schemaJson) {
        // 从已发布的清单快照里取出全部工具名。
        List<String> names = mcpProtocolClientSupport.toolNames(schemaJson);
        // 恰好一个才敢返回；零个或多个都返回 null，交由调用方明确报错。
        return names.size() == 1 ? names.get(0) : null;
    }

    /**
     * 从一串候选值里取出第一个有内容的文本。
     *
     * <p>用于在多个可能的字段里找工具名。按参数顺序体现优先级，找到第一个就停，
     * 因此调用方传入的顺序本身就是「优先信谁」的规则。</p>
     *
     * <p>注意副作用：调用方在传参时会用 remove 从参数表里摘除路由字段，
     * 所以即使某个候选值最终没被采用，它也已经从参数表里被摘掉了——这正是期望行为。</p>
     */
    private String firstText(Object... values) {
        // 一个候选都没有时直接返回空，交由调用方处理。
        if (values == null) {
            // 没有任何候选值时返回空，由调用方决定怎么报错。
            return null;
        }
        // 按传入顺序逐个尝试，体现优先级。
        for (Object value : values) {
    // 统一转成字符串再判断，避免不同类型的值处理方式不一致。
            String text = stringValue(value);
     // 找到第一个非空白值立即返回，后面的候选不再看。
            if (text != null && !text.isBlank()) {
                // 返回第一个有内容的候选值，后面的候选不再看。
                return text;
            }
        }
        // 所有候选都为空，返回空表示没找到。
        return null;
    }

    /**
     * 在领取执行权之前做最后一次前置校验：工具标识和可信身份必须齐全。
     *
 * <p>为什么必须放在最前面：领执行权会写库、会加行锁，而后面还会真的调外部系统。
     * 身份不全时租户隔离、幂等键、审计归属全部失效，此时任何一步都不该发生。</p>
     *
     * <p>租户和用户为空一律拒绝：即使模型给了参数，也绝不用模型给的值来填身份——
   * 模型可能被提示词注入操纵，填成别人的租户就等于跨租户越权。</p>
     */
    private void checkInvoke(ToolCatalogEntity tool, ToolInvokeContextEntity context) {
        // 工具对象或工具编号缺失，说明上游解析出了问题，此时无从判断该调用什么，直接失败。
        if (tool == null || tool.getToolId() == null || tool.getToolId().isBlank()) {
            // 工具标识缺失时立刻失败，避免带着空标识去写审计和调外部系统。
            throw new AppException("TOOL_NOT_FOUND", "工具不存在");
        }
        // 租户或用户缺失就失败关闭：这两项决定数据隔离和审计归属，绝不接受用模型给的值来补。
        if (context == null || blank(context.getTenantId()) || blank(context.getUserId())) {
            // 身份不全时失败关闭，绝不拿模型给的参数来补租户和用户。
            throw new AppException("TOOL_CONTEXT_INVALID", "工具调用身份不完整");
        }
    }

    /**
     * 把对象序列化成 JSON 文本，失败时退化成空对象。
     *
     * <p>为什么不抛异常：它只服务于审计记录和请求体拼装这类「记录性」需求。
     * 因为某个字段序列化不了就让整次工具调用失败，代价远大于少记一段入参。</p>
     *
     * <p>null 会被当成空对象处理，保证审计列里永远是合法 JSON 而不是字面量 null。</p>
     */
    private String toJson(Object value) {
   // 审计序列化失败退化为空对象，不影响门禁本身。
        try {
            // 正常路径：序列化成 JSON 文本写进审计。
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<>() : value);
        } catch (Exception e) {
            // 序列化失败时退化成空对象，保证审计列里仍是合法 JSON。
            return "{}";
        }
    }

    /**
     * 把任意对象安全地转成字符串，null 依然返回 null。
     *
     * <p>用于读取模型给的参数：那些值的类型完全不受控，强制转换会抛类型异常。
     * 保留 null 而不是转成 "null" 字面量，是为了让上层的空白判断能正确识别「没给这个字段」。</p>
     */
    private String stringValue(Object value) {
        // 保留 null 不转成字面量，这样上层的空白判断才能识别出「模型没给这个字段」。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 判断一个身份字段是否等于没有值（空引用或全是空白字符）。
     *
     * <p>连空白串也算空很关键：空串若被当成有效租户带进查询和幂等键，
     * 会造成隔离失效或幂等键失去意义，而这类问题在日志里几乎看不出来。</p>
     */
    private boolean blank(String value) {
        // 空引用和纯空白都算缺失，防止空串被当成有效身份带进查询。
        return value == null || value.isBlank();
    }

    /**
     * 取第一个有内容的字符串，用于给可能缺失的文案补一句兜底说明。
     *
     * <p>当前只服务于结果重放：历史失败记录可能没存下错误文案，
     * 此时给一句「此前已失败」也比返回空字符串有用——空字符串会让模型以为没有错误信息。</p>
     */
    private String defaultString(String value, String defaultValue) {
        // 有值就用原值，没有才用兜底文案，避免给模型一个空错误原因。
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
  * 把异常翻译成一句可以安全交给大模型的错误文案。
  *
     * <p>这是一道信息安全边界。这段文案会被写进返回值，然后进入模型下一轮的提示词，
     * 有可能被模型复述给用户看。所以只能给业务层面的可读原因，绝不能带堆栈、SQL、内部地址、
     * 更不能带凭证信息。</p>
     *
     * <p>数据流：异常 → 是业务异常且有文案则用它 → 否则用异常消息 → 消息为空则用异常类名 → 裁剪长度 → 返回。</p>
     *
     * <p>为什么消息为空要退回类名：直接返回空串会让模型以为「失败了但没有原因」，
     * 类名至少能让模型和排查人员判断这是超时、空指针还是解析失败。</p>
     */
    private String safeMessage(Exception e) {
      // 业务异常的文案是我们自己写的、面向用户的，可以安全展示。
        if (e instanceof AppException appException && appException.getInfo() != null && !appException.getInfo().isBlank()) {
            // 业务异常文案是我们自己面向用户写的，裁剪长度后可以安全交给模型。
            return truncate(appException.getInfo());
        }
        // 其他异常只取一句消息，堆栈留在日志里，不进模型上下文。
        String message = e.getMessage();
// 消息为空时退回类名，保证错误文案里永远有可用信息。
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : truncate(message);
    }

    /**
     * 把返回给模型的文本裁剪到长度上限以内。
     *
     * <p>为什么必须裁剪：这段文本会进入下一轮提示词。超长会挤掉对话历史，
     * 表现为模型「忘记」前文；更长时请求会直接被模型拒绝。</p>
     *
 * <p>为什么选择直接截断尾部：截断是确定性的、零额外开销的方案；做摘要要再调一次模型，
     * 既慢又可能丢失关键信息。上限设得远大于常见返回值，只兜异常情况。</p>
     */
    private String truncate(String value) {
        // 没超限就原样返回，避免不必要的字符串复制。
        if (value == null || value.length() <= MAX_RESULT_LENGTH) {
            // 未超限时原样返回，避免不必要的字符串复制。
            return value;
        }
        // 超限时保留前面部分，尾部丢弃。
        return value.substring(0, MAX_RESULT_LENGTH);
    }

  /**
     * MCP 路由结论的小载体：远程要调哪个工具，以及已经剔除路由字段的纯业务参数。
     *
   * <p>用 record 是因为它只在一次调用内部流转，用完即弃，不参与任何持久化或状态判断。
     * 把两个值绑在一起返回，是为了避免调用方拿到工具名却忘了用剔除后的参数——
  * 那会把 toolName 这类路由字段一起发给远程，触发远程的参数校验失败。</p>
     */
    private record McpCallCommand(String toolName, Map<String, Object> arguments) {
    }

    /**
     * 一次工具执行产生的模型结果和服务端审计结果。
     *
     * <p>普通 Skill/MCP 只返回统一结果；平台工具还会返回不向模型公开的审计字段。</p>
     *
     * @param modelResult 可返回给模型的执行结果
     * @param auditResult 仅供服务端事件和审计持久化的字段
     * @param platform 是否为平台内置工具结果
     */
    private record ToolExecutionResult(Object modelResult, Map<String, Object> auditResult, boolean platform) {

        /** 将普通 Skill/MCP 结果转换为统一执行结果。 */
        private static ToolExecutionResult standard(Object result) {
            return new ToolExecutionResult(result, Map.of(), false);
        }

        /** 将平台工具的模型字段与审计字段分别保存。 */
        private static ToolExecutionResult platform(Map<String, Object> modelResult, Map<String, Object> auditResult) {
            return new ToolExecutionResult(modelResult == null ? Map.of() : modelResult,
                    auditResult == null ? Map.of() : auditResult, true);
        }

        /** 生成持久化工具调用日志使用的 JSON，编码失败时返回空对象。 */
        private String auditJson(ObjectMapper objectMapper) {
            try {
                if (!platform) return objectMapper.writeValueAsString(Map.of("result", modelResult));
                return objectMapper.writeValueAsString(Map.of("modelResult", modelResult,
                        "auditResult", auditResult));
            } catch (Exception ignored) {
                return "{}";
            }
        }

        /** 返回可写入工作流事件的审计字段；普通工具没有扩展审计字段。 */
        private Map<String, Object> eventAuditResult() {
            return platform ? auditResult : Map.of();
        }
    }
}
