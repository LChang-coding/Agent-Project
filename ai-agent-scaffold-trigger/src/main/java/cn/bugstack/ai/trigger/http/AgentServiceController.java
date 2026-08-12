package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.run.model.RunStreamEntity;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationMetadataService;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 对外提供「查配置、建会话、一次性对话、流式对话」四个 HTTP 入口。
 *
 * <p>所属层次：触发器层（trigger），是整个系统最外面的一层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端和外部接入方，通过 /api/v1/ 下的 HTTP 接口调用。</p>
 *
 * <p>它向下调用什么：
 * 1) {@code IChatService}：真正创建会话、保存消息、跑 Agent 或工作流；
 * 2) {@code ActiveRunRegistry}：让「取消运行」的请求能立刻掐断本机上正在推送的流；
 * 3) {@code RagAnswerCitationMetadataService}：在回答落库后取出这次回答引用了哪些资料。</p>
 *
 * <p>它不负责什么：不校验业务规则、不写数据库、不做上下文裁剪、不调用大模型、不编译和执行工作流 DAG。
 * 这里只做三件事：识别请求走Agent 还是走工作流、把异常翻译成统一响应码、把领域事件流转成 SSE 事件推给前端。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    /**
     * 会话与对话的领域入口。
     *
     * <p>建会话、存消息、启动运行、拿事件流全部走它；控制器不碰数据库，也不直接调用大模型。
     * 所有权限校验和参数合法性判断都在它内部完成，这里拿到异常只负责翻译成响应码。</p>
     */
    @Resource
    private IChatService chatService;

    /**
     * 本机「正在跑的运行」登记表，key 是 runId。
     *
     * <p>数据库里保存的是运行的最终状态，这张表只存活在当前 JVM 内存里，作用是让取消请求能立刻找到
     * 对应的订阅并掐断它。如果这里没登记，用户点「停止」就只能等流自己跑完，前端会一直转圈。</p>
     */
    @Resource
    private ActiveRunRegistry activeRunRegistry;

    /**
     * 回答引用（citation）元数据的读取服务。
     *
     * <p>一次回答说了哪些话、引用了哪几篇文档，是在消息落库时一起存进metadata 的。
     * 这里在流结束或同步回答完成后再读一次，保证返回给前端的引用和数据库里的消息完全一致，
     * 不会出现「界面显示引用了文档，但数据库其实没存」这种对不上的情况。</p>
     */
    @Resource
    private RagAnswerCitationMetadataService citationMetadataService;

    /**
     * 查询当前租户能用的智能体（Agent）列表，供前端下拉选择。
     *
     * <p>输入：无参数，租户身份由上下文携带，因此不同租户看到的列表天然隔离。</p>
     *
     * <p>输出：只包含 agentId、名称、描述三个字段的精简列表，模型配置、提示词等内部信息不外泄。</p>
     *
     * <p>不写数据库、不改状态、不发事件。业务异常返回领域给出的错误码，未知异常统一收敛成系统错误码，
     * 保证前端永远拿到结构一致的响应而不是 HTTP 500 页面。</p>
     */
    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        // 整段包在 try 里：无论下层抛什么，前端都必须拿到一个能解析的 Response，而不是异常堆栈。
        try {
            // 记录一次查询入口，方便排查「前端列表为空」时确认请求到底有没有打进来。
            log.info("查询智能体配置列表");

            // 向领域层要当前租户可用的 Agent 配置；配置来源和缓存策略由领域层决定，这里不关心。
            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            // 逐个把领域配置对象裁剪成对外 DTO：只保留前端要展示的三个字段，其余内部配置不暴露。
            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                // 为每个Agent 新建一个对外响应对象，避免直接把领域对象序列化出去。
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                // 带上 Agent 编号，前端后续创建会话时要原样传回来。
                responseDTO.setAgentId(agentConfig.getAgentId());
                // 带上展示名称，用户在界面上看到的就是这个。
                responseDTO.setAgentName(agentConfig.getAgentName());
                // 带上用途描述，帮助用户判断该选哪个智能体。
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                responseDTO.setOrchestrationRole(agentConfig.getOrchestrationRole());
                responseDTO.setCategory(agentConfig.getCategory());
                responseDTO.setBestFor(agentConfig.getBestFor());
                responseDTO.setNotFor(agentConfig.getNotFor());
                responseDTO.setCapabilities(agentConfig.getCapabilities());
                responseDTO.setAllowedSubAgentIds(agentConfig.getAllowedSubAgentIds());
                // 把裁剪好的对象交回流里，参与最终收集。
                return responseDTO;
            }).collect(Collectors.toList());

            // 组装成功响应：成功码 + 成功文案 + 裁剪后的列表。
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            // 这是领域层主动抛出的业务异常，错误码和文案都是设计好的，可以原样透传给前端展示。
            log.error("查询智能体配置列表异常", e);
            // 用业务错误码返回，不带 data；前端据此提示具体原因。
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            // 走到这里说明是没预料到的故障（空指针、下游超时等），细节只能留在日志里。
            log.error("查询智能体配置列表失败", e);
            // 对外统一成系统错误码，避免把内部异常信息泄露给调用方。
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 建立一次新的对话会话，返回服务端生成的 sessionId。
     *
     * <p>关键输入：请求里若带 workflowId，说明这次要跑数据库里配置的工作流，会顺带绑定工作流版本和模型；
     * 否则按 agentId 建普通 Agent 会话。</p>
     *
     * <p>输出：sessionId。后续所有消息、运行记录、上下文都挂在这个 sessionId 下面，
     * 前端必须保存它，否则下一轮对话就变成了新会话，历史全丢。</p>
     *
     * <p>会写数据库：会话记录由领域层落库。用户身份以认证上下文为准，请求体里的 userId 只是旧接口的兼容入口，
     * 防止调用方伪造别人的身份来建会话。</p>
     */
    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        // 建会话要写库，任何一步失败都必须转成统一响应，不能把异常直接抛给前端。
        try {
            // 以登录态里的用户为准；请求体里的 userId 只在没有认证上下文时才生效，防止越权建会话。
            String userId = trustedUserId(requestDTO.getUserId());
            // 落一条入口日志，出问题时能确认这次会话到底是按哪个 Agent 还是哪个工作流建的。
            log.info("创建会话 agentId:{} workflowId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getWorkflowId(), userId);
            // 按请求里有没有 workflowId 分流：走工作流会话（绑定版本和模型）还是普通 Agent 会话。
            String sessionId = hasWorkflow(requestDTO)
                    ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                    : chatService.createSession(requestDTO.getAgentId(), userId);

            // 新建对外响应对象，只把 sessionId 这一个必要信息带回去。
            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            // 回填服务端生成的会话编号，前端后续每轮对话都要原样带上它。
            responseDTO.setSessionId(sessionId);

            // 返回成功响应，前端拿到 sessionId 才能发第一条消息。
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            // 领域层拒绝了这次创建（例如 Agent 不存在、工作流版本不可用），错误码可直接透传。
            log.error("查询智能体配置列表异常", e);
            // 带业务错误码返回，不生成会话，前端应提示用户换配置重试。
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            // 未知故障：会话可能根本没建成，日志里带上 Agent 和用户便于定位。
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), trustedUserId(requestDTO.getUserId()), e);
            // 统一系统错误码返回，前端不会拿到 sessionId，必须重新发起创建。
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 兼容老前端用 GET + 查询参数建会话的写法。
     *
     * <p>本身不含任何业务逻辑，只是把两个查询参数装进请求对象，然后复用 POST 版本的完整流程，
     * 保证新老入口的校验、落库和错误处理完全一致，不会出现两套行为。</p>
     */
    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        // 造一个和 POST 入口同构的请求对象，后面才能直接复用同一套逻辑。
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        // 放入要使用的智能体编号。
        requestDTO.setAgentId(agentId);
        // 放入调用方传来的用户编号；它是否被采纳，仍由 POST 流程里的可信身份判断决定。
        requestDTO.setUserId(userId);
        // 直接转交给 POST 版本执行，避免老接口出现一套单独的、容易走偏的实现。
        return createSession(requestDTO);
    }

    /**
     * 一次性对话：发一条消息，等到全部结果生成完再一起返回。
     *
     * <p>各层职责：
     * 第一层：确定可信用户，并在前端没给 sessionId 时先补建一个会话，保证消息有归属。
     * 第二层：按有无 workflowId 分流，交给领域层创建运行记录并启动执行。
     * 第三层：阻塞等待整条流跑完，把流里的分片合并成一段完整文本。
     * 第四层：读取已落库的引用快照，拼装最终响应返回前端。</p>
     *
     * <p>数据流：
     * HTTP 请求
     * → 可信用户判定
     * → 会话补建（可选）
     * → 启动运行并保存用户消息
     * → 阻塞收集流内全部分片
     * → 合并成完整回答文本
     * → 查询引用快照
     * → 组装响应
     * → 返回前端</p>
     *
     * <p>会写数据库（消息、运行记录由领域层落库），会调用大模型或执行工作流。
     * 主要失败情形：会话不属于当前用户、工作流配置不可用、模型调用失败；
     * 前两者是业务异常按原码返回，后者通常收敛成系统错误码。</p>
     */
    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        // 整个对话过程都在 try 里：模型调用、工作流执行随时可能失败，但响应结构必须稳定。
        try {
            // 以登录用户为准判定这条消息属于谁，防止请求体伪造 userId 去写别人的会话。
            String userId = trustedUserId(requestDTO.getUserId());
            // 记录本轮对话的关键身份信息，便于按 agentId/workflowId 追查问题。
            log.info("智能体对话 agentId:{} workflowId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getWorkflowId(), userId);
            // 先取前端带来的会话编号；它决定这条消息挂到哪段历史上。
            String sessionId = requestDTO.getSessionId();
            // 第一层：前端没给会话（例如用户直接发第一句话），必须先建会话，否则消息无处存放。
            if (sessionId == null || sessionId.isEmpty()) {
                // 未提供会话时先建立服务端会话，后续消息和运行都以该 sessionId 隔离。
                sessionId = hasWorkflow(requestDTO)
                        ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                        : chatService.createSession(requestDTO.getAgentId(), userId);
            }

            // 保存本次运行的身份和事件流；两条分支产出的流元素类型不同，所以用通配泛型统一持有。
            RunStreamEntity<?> runStream;
            // 收集流里的全部分片文本，稍后按分支各自的规则合并。
            List<String> messages;
            // 提前判定一次走哪条路径，后面组装响应时还要复用这个结论，避免重复判断出现分歧。
            boolean workflowRequest = hasWorkflow(requestDTO);
            // 第二层：分流执行。有workflowId 走 DAG 工作流，否则走单个 Agent。
            if (workflowRequest) {
                // ChatService 保存 message 后将其作为 DAG 输入，附件ID也在领域层校验并绑定。
                RunStreamEntity<String> workflowRun = chatService.startWorkflowMessageTextStream(
                        requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(),
                        userId, sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(),
                        requestDTO.getAttachmentIds());
                // 记住运行身份，响应里的 runId、上下文版本都从它取。
                runStream = workflowRun;
                // 第三层：阻塞等工作流把所有输出文本吐完；同步接口本来就要等到有完整结果才能返回。
                messages = workflowRun.getStream().toList().blockingGet();
            } else {
                // 普通 Agent 由 ChatService 创建 Run，再把 message 交给 ADK Runner 分析。
                RunStreamEntity<Event> agentRun = chatService.startMessageStream(requestDTO.getAgentId(), userId,
                        sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(), requestDTO.getAttachmentIds());
                // 同样先留住运行身份，供响应回填 runId 与上下文版本。
                runStream = agentRun;
                // 第三层：把每个 ADK 事件转成文本后阻塞收齐；注意这些文本是「累计式」的，后面要去重合并。
                messages = agentRun.getStream().map(Event::stringifyContent).toList().blockingGet();
            }

            // 第四层：开始组装对外响应对象。
            ChatResponseDTO responseDTO = new ChatResponseDTO();
            // 回填会话编号（可能是刚补建的），前端要用它继续下一轮对话。
            responseDTO.setSessionId(sessionId);
            // 工作流的每段输出彼此独立，直接换行拼接；Agent 的分片是累计文本，必须去重合并否则内容会重复好几遍。
            responseDTO.setContent(workflowRequest ? String.join("\n", messages) : mergeAgentContents(messages));
            // 回填运行编号，前端凭它做取消、查状态和反馈。
            responseDTO.setRunId(runStream.getRun().getRunId());
            // 同步接口执行到这里已经拿到完整结果，因此对外声明为已完成状态。
            responseDTO.setRunStatus("completed");
            // 回填上下文版本号，前端和后续请求依靠它判断历史是否被改写过。
            responseDTO.setContextRevision(runStream.getRun().getCurrentContextRevision());
            // 最终回答提交后再读取引用快照，确保响应引用与数据库消息一致。
            applyCitationSnapshot(responseDTO, citationMetadataService.queryRunAnswer(
                    TenantContextHolder.getTenantId(), userId, sessionId, runStream.getRun().getRunId()));

            // 返回完整回答，前端一次性渲染即可。
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            // 领域层明确拒绝：可能是会话越权、工作流版本无效或附件不合法，错误码可直接给前端。
            log.error("智能体对话异常", e);
            // 只回错误码和文案，不带内容；此时数据库里可能已存下用户消息，但没有有效回答。
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            // 未预期故障（模型超时、节点执行崩溃等），日志保留身份信息以便复现。
            log.error("智能体对话失败 agentId:{} userId:{}", requestDTO.getAgentId(), trustedUserId(requestDTO.getUserId()), e);
            // 统一系统错误码返回，前端提示重试即可，不暴露内部异常细节。
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 流式对话：建立一条 SSE 长连接，边生成边把内容推给前端。
     *
     * <p>各层职责：
     * 第一层：先确定并下发 traceId。它必须在任何业务动作之前发出，否则一旦后面立刻失败，
     *         前端就拿不到可用于查日志的线索。
     * 第二层：确定可信用户，并在缺少 sessionId 时补建会话，然后把会话号发给前端。
     * 第三层：按有无 workflowId 分流，让领域层创建运行记录并启动执行。
     * 第四层：先登记取消句柄，再下发 run 事件，最后才订阅数据流。顺序反了会出现「用户点了停止但没人接」的漏洞。
     * 第五层：订阅流，把每个事件转成 message 事件推送，结束时补发引用信息并关闭连接。
     * 第六层：任何环节异常都转成一条 error 事件并关闭连接，避免前端永远等待一个不会结束的流。</p>
     *
     * <p>数据流：
     * HTTP 请求
     * → 建立 SseEmitter（3 分钟超时）
     * → 下发 trace 事件
     * → 可信用户判定
     * → 会话补建（可选）→ 下发 session 事件
     * → 启动运行
     * → 登记取消句柄
     * → 下发 run 事件
     * → 订阅领域事件流
     * → 逐条推送 message 事件
     * → 结束时推送 citation_validation 事件
     * → 关闭连接</p>
     *
     * <p>会写数据库、会调用模型或执行工作流。返回的是 emitter 本身，方法返回时数据往往还在后台继续推送，
     * 因此这里的 try/catch 只覆盖「建立阶段」的失败，流内部的异常由订阅的错误回调处理。</p>
     */
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        // 建立 SSE 通道并设定 3 分钟上限：超时后会走onTimeout，防止长时间挂死的连接一直占用线程。
        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);
        // Trace 必须在任何业务事件前确定并返回，用户才能据此查询完整链路日志。
        String traceId = TraceContext.ensureTraceId();
        // 只保护「建流阶段」：这一段失败必须立刻给前端一条 error 事件并关闭，否则前端会一直等。
        try {
            // 第一层：先把链路标识推给前端，后续即使立刻报错，用户也能带着这个 ID 找日志。
            sendTraceMetadata(emitter, traceId);
            // 第二层：以登录态用户为准，防止请求体伪造身份往别人的会话里写消息。
            String userId = trustedUserId(requestDTO.getUserId());
            // 记录受理明细（消息长度、附件数量而非内容本身），既能排查问题又不把用户原文写进日志。
            log.info("流式对话已受理 agentId:{} workflowId:{} modelCode:{} userId:{} sessionId:{} messageLength:{} attachmentCount:{}",
                    requestDTO.getAgentId(), requestDTO.getWorkflowId(), requestDTO.getModelCode(), userId,
                    requestDTO.getSessionId(), requestDTO.getMessage() == null ? 0 : requestDTO.getMessage().length(),
                    requestDTO.getAttachmentIds() == null ? 0 : requestDTO.getAttachmentIds().size());
            // 取前端带来的会话编号，决定这条消息接在哪段历史后面。
            String sessionId = requestDTO.getSessionId();
            // 前端没带会话号时必须先建一个，否则消息和运行记录没有归属。
            if (sessionId == null || sessionId.isEmpty()) {
                // 同样按有无 workflowId 分流建会话，保持与同步接口一致的行为。
                sessionId = hasWorkflow(requestDTO)
                        ? chatService.createWorkflowSession(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId)
                        : chatService.createSession(requestDTO.getAgentId(), userId);
            }
            // 立刻把会话号告诉前端：即使是刚补建的会话，前端也能马上用它续聊。
            emitter.send(SseEmitter.event().name("session").data(sessionId));

            // 用引用盒子存订阅句柄：句柄要等订阅之后才有，但取消回调必须提前注册，所以先占位。
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            // 记录「是否已经收到取消请求」，并保证取消动作只执行一次，避免重复关闭连接。
            AtomicBoolean interruptRequested = new AtomicBoolean(false);
            // 运行编号先置空，成功创建运行后才会有值。
            String runId = null;
            // 第三层：分流执行，工作流和普通 Agent 走两套启动方法。
            if (hasWorkflow(requestDTO)) {
                // 创建 Run 后先注册取消句柄，再订阅执行流，封闭立即取消的竞态窗口。
                RunStreamEntity<String> runStream = chatService.startWorkflowMessageTextStream(
                        requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(),
                        userId, sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(),
                        requestDTO.getAttachmentIds());
                // 取出本次运行编号，它是取消、查状态和关联引用的唯一钥匙。
                runId = runStream.getRun().getRunId();
                // 第四层：先把取消句柄挂进注册表，这样用户在 run 事件刚发出时点停止也能被接住。
                registerActiveStream(runId, emitter, disposableRef, interruptRequested);
                // 把运行元数据一次性告诉前端：有了 runId 前端才能发取消请求。
                emitter.send(SseEmitter.event().name("run").data(java.util.Map.of(
                        "runId", runId,
                        "status", runStream.getRun().getStatus().name().toLowerCase(),
                        "contextRevision", runStream.getRun().getCurrentContextRevision(),
                        "traceId", traceId)));
                // 如果在这短短一瞬用户已经取消，就不要再订阅了，否则会白跑一遍工作流。
                if (!interruptRequested.get()) {
                    // 第五层：订阅工作流文本流并保存句柄；attachDisposable 负责补上「订阅刚建好就被取消」的漏网情况。
                    attachDisposable(disposableRef, interruptRequested,
                            subscribeWorkflowTextStream(runStream.getStream(), emitter, disposableRef,
                                    TenantContextHolder.getTenantId(), userId, sessionId, runId, traceId));
                }
            } else {
                // 普通 Agent 分支：由领域层创建运行记录并把消息交给 ADK Runner 执行。
                RunStreamEntity<Event> runStream = chatService.startMessageStream(requestDTO.getAgentId(), userId,
                        sessionId, requestDTO.getMessage(), requestDTO.getRequestedRunId(), requestDTO.getAttachmentIds());
                // 同样取出运行编号，作为取消与引用查询的依据。
                runId = runStream.getRun().getRunId();
                // 第四层：同样先登记取消句柄再发 run 事件，保证取消请求不会落空。
                registerActiveStream(runId, emitter, disposableRef, interruptRequested);
                // 下发运行元数据，前端据此显示状态并具备取消能力。
                emitter.send(SseEmitter.event().name("run").data(java.util.Map.of(
                        "runId", runId,
                        "status", runStream.getRun().getStatus().name().toLowerCase(),
                        "contextRevision", runStream.getRun().getCurrentContextRevision(),
                        "traceId", traceId)));
                // 已被取消就不再订阅，省下一次无意义的模型调用。
                if (!interruptRequested.get()) {
                    // 第五层：订阅 ADK 事件流，句柄交给 attachDisposable 统一管理取消竞态。
                    attachDisposable(disposableRef, interruptRequested,
                            subscribeAgentEventStream(runStream.getStream(), emitter, disposableRef,
                                    TenantContextHolder.getTenantId(), userId, sessionId, runId, traceId));
                }
            }
        } catch (Exception e) {
            // 第六层：建流阶段任何失败都记日志，细节不进SSE，避免把内部信息推给浏览器。
            log.error("流式对话失败", e);
            // 给前端补发一条带 traceId 的 error 事件并关闭连接，前端才能停止等待并提示用户。
            completeSseWithError(emitter, e, traceId);
        }
        // 把通道交回 Spring；此时数据可能仍在后台线程持续推送，方法返回不代表对话结束。
        return emitter;
    }

    /**
     * 把这次运行登记进本机注册表，并挂上连接生命周期的四个回调。
     *
     * <p>为什么必须在下发 run 事件之前调用：前端一拿到 runId 就可能立刻点「停止」。
     * 如果那时还没登记，取消请求找不到目标，流会继续跑到底，用户界面却已经显示停止了。</p>
     *
     * <p>四个回调分别兜住四种结束方式：
     * 取消动作 → 用 CAS 保证只生效一次，先断订阅再关连接；
     * 正常完成 → 断订阅并从注册表摘掉，防止内存里堆积死条目；
     * 连接超时 → 优先走统一的中断入口，走不通才自己兜底关闭；
     * 连接出错（如浏览器关页）→ 断订阅并清理登记。</p>
     *
     * <p>不写数据库、不发业务事件，只管进程内的资源清理。</p>
     */
    private void registerActiveStream(String runId, SseEmitter emitter,
                                      AtomicReference<Disposable> disposableRef,
                                      AtomicBoolean interruptRequested) {
        // 没有运行编号就无从登记，也无从取消，直接返回避免往注册表塞空键。
        if (runId == null) return;
        // 把「如何中断这次运行」登记下来，取消接口只需按 runId 触发这个动作。
        activeRunRegistry.register(runId, () -> {
            // CAS 保证并发下只有一次取消真正生效，重复取消不会重复关闭连接。
            if (interruptRequested.compareAndSet(false, true)) {
                // 先掐断上游订阅，停止继续生成内容，省掉无用的模型调用。
                dispose(disposableRef);
                // 再正常关闭 SSE，让前端收到结束信号而不是一直挂着。
                emitter.complete();
            }
        });
        // 连接正常结束时的清理钩子。
        emitter.onCompletion(() -> {
            // 确保上游订阅一定被释放，避免推送到一个已经关闭的连接上。
            dispose(disposableRef);
            // 从注册表摘除本次运行，否则内存里的条目会越积越多。
            activeRunRegistry.remove(runId);
        });
        // 超过 3 分钟未结束时的兜底处理。
        emitter.onTimeout(() -> {
            // 优先走统一中断入口；返回 false 说明注册表里已经没有这次运行了。
            if (!activeRunRegistry.interrupt(runId)) {
                // 中断入口没接住，就自己释放订阅。
                dispose(disposableRef);
                // 并主动关闭连接，避免线程和连接资源被长期占用。
                emitter.complete();
            }
        });
        // 连接异常中断（用户关页、网络断开）时的清理钩子。
        emitter.onError(error -> {
            // 断开订阅，停止向已经不存在的客户端生成内容。
            dispose(disposableRef);
            // 清掉注册表条目，防止残留无效的中断句柄。
            activeRunRegistry.remove(runId);
        });
    }

    /**
     * 保存订阅句柄，并补上「订阅刚建立就被取消」这一段时间差造成的漏洞。
     *
     * <p>取消回调是在订阅之前注册的，那一刻句柄还是空的。如果用户正好在这个窗口里点了停止，
     * 取消动作释放的是一个空句柄，等于没生效。所以这里先存入句柄，再回头确认一次：
     * 只要发现已经有人请求过取消，就立刻把刚建立的订阅释放掉，避免出现「已取消但仍在生成」的运行。</p>
     *
     * <p>不写数据库、不推送事件，纯粹的并发安全补偿。</p>
     */
    private void attachDisposable(AtomicReference<Disposable> disposableRef,
                                  AtomicBoolean interruptRequested,
                                  Disposable disposable) {
        // 先把真实句柄放进引用盒子，之后的取消动作才有东西可释放。
        disposableRef.set(disposable);
        // 回头检查：如果取消请求早于本次赋值到达，取消当时释放的是空句柄，需要在这里补做一次。
        if (interruptRequested.get()) {
            // 立刻释放刚建立的订阅，保证用户的取消意图最终一定生效。
            dispose(disposableRef);
        }
    }

    /**
     * 直接由请求参数启动并订阅工作流文本流（旧调用路径，当前主流程未使用）。
     *
     * <p>与主流程的区别：它不创建可取消的运行记录，也不在结束时补发引用事件，
     * 结束时只是简单关闭连接，因此拿不到 runId、无法取消、前端也收不到引用信息。</p>
     *
     * <p>数据流：请求参数 → 领域层执行工作流 → 文本分片 → message 事件 → 前端；
     * 出错时推送 error 事件，正常结束时直接关闭连接。</p>
     */
    private Disposable subscribeWorkflowTextStream(ChatRequestDTO requestDTO,
                                                   String userId,
                                                   String sessionId,
                                                   SseEmitter emitter,
                                                   AtomicReference<Disposable> disposableRef) {
        // 让领域层按工作流配置执行，并订阅它产出的文本流。
        return chatService.handleWorkflowMessageTextStream(requestDTO.getWorkflowId(), requestDTO.getWorkflowVersion(), requestDTO.getModelCode(), userId, sessionId, requestDTO.getMessage())
                .subscribe(
                        // 每来一段文本就推一条 message 事件给前端；推送失败会顺带断开订阅。
                        content -> sendMessage(emitter, disposableRef, content),
                        // 执行出错时转成 error 事件并关闭连接，前端才能停止等待。
                        error -> completeSseWithError(emitter, error),
                        // 正常结束就直接关闭连接，这条旧路径不补发引用信息。
                        emitter::complete
                );
    }

    /**
     * 订阅已经创建好的工作流文本流，是流式对话工作流分支的实际推送逻辑。
     *
     * <p>输入是领域层已启动的文本流以及本次运行的完整身份（租户、用户、会话、运行、链路）。
     * 身份要一路带着，因为流结束后还要凭它去查这次回答引用了哪些资料。</p>
     *
     * <p>数据流：
     * 工作流节点输出
     * → 文本分片
     * → message 事件推送前端
     * → 流结束
     * → 查询引用快照
     * → citation_validation 事件
     * → 关闭连接；
     * 中途出错则替换为带 traceId 的 error 事件并关闭。</p>
     *
     * <p>返回订阅句柄，交给上层登记，用户取消时凭它掐断执行。</p>
     */
    private Disposable subscribeWorkflowTextStream(Flowable<String> stream,
                                                    SseEmitter emitter,
                                                    AtomicReference<Disposable> disposableRef,
                                                    String tenantId, String userId, String sessionId, String runId,
                                                    String traceId) {
        // 订阅文本流并返回句柄；三个回调分别覆盖有数据、出错、正常结束三种情况。
        return stream.subscribe(
                // 工作流每产出一段最终文本，就原样作为一条 message 事件推给前端。
                content -> sendMessage(emitter, disposableRef, content),
                // 出错时带上 traceId 推error 事件并关闭，用户可凭 traceId 反馈问题。
                error -> completeSseWithError(emitter, error, traceId),
                // 正常跑完后再查一次落库的引用快照，补发引用事件后才关闭连接。
                () -> completeSseWithCitation(emitter, tenantId, userId, sessionId, runId, traceId)
        );
    }

    /**
     * 直接由请求参数启动并订阅Agent 事件流（旧调用路径，当前主流程未使用）。
     *
     * <p>与主流程的区别同样是：不创建可取消的运行、结束时不补发引用事件。</p>
     *
     * <p>数据流：请求参数 → ADK执行 → 累计文本事件 → 计算新增片段 → message 事件 → 前端。
     * 注意 ADK 事件里带的是「到目前为止的全部文本」，必须先算差值再推送，否则前端会看到内容不断重复。</p>
     */
    private Disposable subscribeAgentEventStream(ChatRequestDTO requestDTO,
                                                 String userId,
                                                 String sessionId,
                                                 SseEmitter emitter,
                                                 AtomicReference<Disposable> disposableRef) {
        // 记住上一次已经推送过的累计文本，用来算出这次真正新增的部分。
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        // 让领域层执行 Agent 并订阅它产出的事件流。
        return chatService.handleMessageStream(requestDTO.getAgentId(), userId, sessionId, requestDTO.getMessage())
                .subscribe(
                        // 先把事件内容转成文本、再减去已推送部分，只把新增片段发给前端。
                        event -> sendMessage(emitter, disposableRef, streamDelta(lastContentRef, event.stringifyContent())),
                        // 出错时转成 error 事件并关闭连接。
                        error -> completeSseWithError(emitter, error),
                        // 正常结束直接关闭，这条旧路径不补发引用信息。
                        emitter::complete
                );
    }

    /**
     * 订阅已经创建好的 Agent 事件流，是流式对话普通 Agent 分支的实际推送逻辑。
     *
     * <p>核心难点在于 ADK 事件携带的是累计文本而不是增量：第二个事件里包含第一个事件的全部内容。
     * 若直接推送，前端会把同一段话重复渲染多次，所以每次都要先减掉上次已推送的部分。</p>
     *
     * <p>数据流：
     * ADK 事件
     * → 转成累计文本
     * → 与上次内容对比算出新增片段
     * → message 事件推送前端
     * → 流结束
     * → 查询引用快照
     * → citation_validation 事件
     * → 关闭连接；
     * 中途出错则改为带 traceId 的 error 事件并关闭。</p>
     *
     * <p>返回订阅句柄，供上层在用户取消时释放。</p>
     */
    private Disposable subscribeAgentEventStream(Flowable<Event> stream,
                                                  SseEmitter emitter,
                                                  AtomicReference<Disposable> disposableRef,
                                                  String tenantId, String userId, String sessionId, String runId,
                                                  String traceId) {
        // 保存已推送的累计文本；它是判断「哪些字是新的」的唯一依据，初始为空串表示还没推过任何内容。
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        // 订阅事件流并返回句柄，三个回调覆盖数据、异常和完成。
        return stream.subscribe(
                // 把累计文本转成本次新增片段后推送，避免前端重复显示同一段回答。
                event -> sendMessage(emitter, disposableRef, streamDelta(lastContentRef, event.stringifyContent())),
                // 出错时下发带 traceId 的 error 事件并关闭连接。
                error -> completeSseWithError(emitter, error, traceId),
                // 正常结束后读取已落库的引用快照，补发引用事件再关闭连接。
                () -> completeSseWithCitation(emitter, tenantId, userId, sessionId, runId, traceId)
        );
    }

    /**
     * 补发引用事件并关闭连接的便捷入口（当前主流程未使用，缺少显式 traceId 的调用方可用）。
     *
     * <p>自行补一个链路标识后，转交给带 traceId 的完整实现，保证出错时前端一定能拿到可查询的 traceId。</p>
     */
    private void completeSseWithCitation(SseEmitter emitter, String tenantId, String userId,
                                         String sessionId, String runId) {
        // 现场取出（或生成）当前链路标识，再走统一的收尾逻辑，避免两套实现产生差异。
        completeSseWithCitation(emitter, tenantId, userId, sessionId, runId, TraceContext.ensureTraceId());
    }

    /**
     * 流正常结束后的统一收尾：把这次回答引用了哪些资料告诉前端，然后关闭连接。
     *
     * <p>为什么放在最后：引用信息是随助手消息一起落库的，只有等回答写完才查得到。
     * 提前查会拿到空结果，界面上就会出现「有内容但没有出处」。</p>
     *
     * <p>数据流：租户+用户+会话+运行 → 查询落库的助手消息 → 取出引用校验结果 → 转成对外 DTO
     * → citation_validation 事件 → 关闭连接。</p>
     *
     * <p>查不到快照（例如这次回答没走知识库）时就跳过引用事件，直接正常关闭，不算失败。
     * 查询本身出错则退化成 error 事件，绝不能让连接停在半开状态。</p>
     */
    private void completeSseWithCitation(SseEmitter emitter, String tenantId, String userId,
                                         String sessionId, String runId, String traceId) {
        // 查库和推送都可能失败，但无论如何都必须给连接一个明确的结局。
        try {
            // 按可信身份读取本次运行对应的助手消息及其引用校验结果。
            RagAnswerCitationMetadataService.AnswerSnapshot snapshot = citationMetadataService.queryRunAnswer(
                    tenantId, userId, sessionId, runId);
            // 只有真的存在引用快照时才发这条事件；没有知识库参与的回答直接跳过。
            if (snapshot != null) {
                // 把消息编号、运行编号和引用明细一起推给前端，前端据此渲染可点击的出处链接。
                emitter.send(SseEmitter.event().name("citation_validation").data(java.util.Map.of(
                        "messageId", snapshot.messageId(), "runId", runId,
                        "validation", toCitationDTO(snapshot.validation()))));
            }
            // 正常关闭连接，前端收到结束信号后停止加载动画。
            emitter.complete();
        } catch (Exception exception) {
            // 收尾阶段出错也要给前端一个明确结局，否则连接会一直半开着。
            completeSseWithError(emitter, exception, traceId);
        }
    }

    /**
     * 把落库的引用快照贴到同步对话的响应上。
     *
     * <p>用于 /chat 接口：流式接口靠单独的 SSE 事件下发引用，同步接口则直接塞进响应体。</p>
     *
     * <p>快照为空说明这次回答没有引用任何资料，此时保持响应原样，不写入空的引用结构，
     * 避免前端把「没有引用」误判成「引用为空的错误状态」。</p>
     */
    private void applyCitationSnapshot(ChatResponseDTO response,
                                       RagAnswerCitationMetadataService.AnswerSnapshot snapshot) {
        // 没有引用快照就什么都不改，让响应保持「无引用」的干净状态。
        if (snapshot == null) return;
        // 回填助手消息编号，前端后续查看某条引用原文时要带上它。
        response.setMessageId(snapshot.messageId());
        // 回填转换后的引用校验结果，包含用了哪些引用、哪些引用不合法。
        response.setCitationValidation(toCitationDTO(snapshot.validation()));
    }

    /**
     * 把领域层的引用校验结果翻译成对外 DTO。
     *
     * <p>这是一道边界：领域对象里可能含有内部字段（例如内容哈希），不能直接序列化给前端。
     * 这里只挑选前端展示和跳转真正需要的字段，一条条搬过去。</p>
     *
     * <p>数据流：领域校验结果 → 状态与四组引用编号集合 → 遍历实际使用的引用
     * → 逐条转成引用 DTO（文档、版本、分块、页码、标题路径）→ 组装成完整 DTO 返回。</p>
     *
     * <p>不查库、不改状态，纯结构转换。注意入参为空会直接抛空指针，调用方必须先判空。</p>
     */
    private RagCitationValidationDTO toCitationDTO(RagAnswerCitationValidation value) {
        // 逐字段搬运：先带上整体校验状态（引用是否全部合法），前端据此决定要不要给出警示。
        return RagCitationValidationDTO.builder().status(value.status().name())
                // 三组编号说明这次检索到了什么、允许引用什么、模型实际引用了什么，以及哪些引用不合法，
                // 前端和排查人员靠它们判断模型是否在编造出处。
                .retrievalIds(value.retrievalIds()).allowedCitationIds(value.allowedCitationIds())
                .usedCitationIds(value.usedCitationIds()).invalidCitationIds(value.invalidCitationIds())
                // 再把每条真正被使用的引用展开成明细：文档、版本、分块、页码、标题路径，
                // 这些是前端把引用渲染成可点击出处、并精确定位到原文位置所必需的。
                .citations(value.usedCitations().stream().map(citation -> RagCitationValidationDTO.CitationDTO.builder()
                        .citationId(citation.citationId()).knowledgeBaseId(citation.knowledgeBaseId())
                        .documentId(citation.documentId()).documentName(citation.documentName())
                        .versionId(citation.versionId()).documentVersion(citation.documentVersion())
                        .generation(citation.generation()).chunkId(citation.chunkId())
                        .pageNumber(citation.pageNumber()).headingPath(citation.headingPath()).build()).toList())
                .build();
    }

    /**
     * 向前端推送一条 message 事件。
     *
     * <p>空内容会被丢弃：增量计算后经常出现空串（同一段文本重复到达），推空事件只会让前端白忙一趟。</p>
     *
     * <p>推送失败通常意味着客户端已经断开。此时继续生成内容毫无意义，所以立刻释放上游订阅，
     * 并用错误方式结束连接，让整条链路尽快收敛而不是继续消耗模型额度。</p>
     */
    private void sendMessage(SseEmitter emitter, AtomicReference<Disposable> disposableRef, String content) {
        // 推送可能因客户端断开而抛异常，必须接住，否则异常会打断整条流的订阅逻辑。
        try {
            // 只有确实有新内容才推送；空串和纯空白直接跳过，减少无意义的网络往返。
            if (content != null && !content.isBlank()) {
                // 以 message 为事件名推送新增文本，前端按事件名追加到当前气泡。
                emitter.send(SseEmitter.event().name("message").data(content));
            }
        } catch (Exception e) {
            // 推送失败一般是客户端已经断开，记日志便于确认是网络问题而非生成问题。
            log.error("流式对话发送失败", e);
            // 立即释放上游订阅，停止继续生成没人接收的内容。
            dispose(disposableRef);
            // 以错误方式结束连接，明确标记这条流没有正常完成。
            emitter.completeWithError(e);
        }
    }

    /**
     * 推送 error 事件的便捷入口（供没有现成 traceId 的调用方使用）。
     *
     * <p>自行补一个链路标识后转交完整实现，保证前端收到的每条错误都带得走的排查线索。</p>
     */
    private void completeSseWithError(SseEmitter emitter, Throwable error) {
        // 现场取出当前链路标识，再走统一的错误收尾，避免出现不带 traceId 的错误事件。
        completeSseWithError(emitter, error, TraceContext.ensureTraceId());
    }

    /**
     * 把任意异常安全地编码成一条 SSE error 事件，并确保连接一定被关闭。
     *
     * <p>区分两类异常：领域层主动抛出的业务异常携带可展示的错误码和文案，可以原样告知用户；
     * 其他异常只给通用错误码，不把内部细节推到浏览器。</p>
     *
     * <p>数据流：异常 → 判定是否业务异常 → 选定错误码与文案 →兜底空文案 → error 事件 → 关闭连接。</p>
     *
     * <p>关键点在finally：即使连error 事件都发不出去（客户端已断开），也必须关闭连接，
     * 否则这条流会一直挂着，占用服务端资源且前端永远等不到结束。</p>
     */
    private void completeSseWithError(SseEmitter emitter, Throwable error, String traceId) {
        // 先按最保守的通用错误码打底，后面能识别出业务异常再覆盖。
        String code = ResponseCode.UN_ERROR.getCode();
        // 同样先取通用错误文案作为兜底。
        String message = ResponseCode.UN_ERROR.getInfo();
        // 业务异常带有设计好的错误码和用户可读文案，可以直接展示给用户。
        if (error instanceof AppException appException) {
            // 换成业务错误码，前端可据此做差异化提示。
            code = appException.getCode();
            // 换成业务文案，比通用「系统异常」更有指导意义。
            message = appException.getInfo();
        }
        // 再兜一层空文案：万一业务异常没写文案，也不能给前端推一条空消息。
        String safeMessage = message == null || message.isBlank() ? ResponseCode.UN_ERROR.getInfo() : message;
        // 推送错误事件本身也可能失败（客户端已断开），所以要接住。
        try {
            // 把错误码、文案和链路标识一起推给前端，用户反馈问题时能带上 traceId。
            emitter.send(SseEmitter.event().name("error").data(java.util.Map.of(
                    "code", code,
                    "message", safeMessage,
                    "traceId", traceId)));
        } catch (Exception sendError) {
            // 连错误事件都发不出去，通常是连接已经断了，用 debug 级别记录即可，不必污染错误日志。
            log.debug("SSE 错误事件发送失败 code:{}", code, sendError);
        } finally {
            // 无论错误事件是否发送成功，都必须关闭连接，防止流永远挂在半开状态。
            emitter.complete();
        }
    }

    /**
     * 在任何业务事件之前，先把本次请求的链路标识发给前端。
     *
     * <p>顺序很关键：只有第一条事件就是 trace，用户遇到「刚点发送就报错」时才有ID 可查。</p>
     *
     * <p>这里不吞异常而是往外抛：连第一条事件都发不出去说明连接根本不可用，
     * 让调用方走统一的错误收尾比在这里假装成功更安全。</p>
     */
    private void sendTraceMetadata(SseEmitter emitter, String traceId) throws java.io.IOException {
        // 以 trace 为事件名推送链路标识，作为整条 SSE 流的第一条事件。
        emitter.send(SseEmitter.event().name("trace").data(java.util.Map.of("traceId", traceId)));
    }

    /**
     * 从「累计文本」中算出本次真正新增的片段。
     *
     * <p>为什么需要它：ADK 事件里带的是从头到现在的全部文本，第 N 个事件包含前 N-1 个事件的内容。
     * 直接推送会让前端把同一段话重复渲染，所以每次都要减去上次已经推过的部分。</p>
     *
     * <p>数据流：本次累计文本 → 判空→ 与上次内容比对 → 若是延长则截取尾部新增；
     * 若不是延长（模型换了一段全新内容）则整段作为新增，同时把它接在已推送记录之后 → 返回应推送的文本。</p>
     *
     * <p>会修改 lastContentRef 这个共享状态，所以必须每条流各用一个实例，不能跨请求复用，
     * 否则两个用户的输出会互相截断。</p>
     */
    private String streamDelta(AtomicReference<String> lastContentRef, String currentContent) {
        // 本次事件没有可用文本（心跳或纯工具调用事件），没有任何东西需要推送。
        if (currentContent == null || currentContent.isBlank()) {
            // 返回空串，由调用方的推送方法负责跳过。
            return "";
        }
        // 取出上次已经推送过的累计文本，作为对比基准。
        String lastContent = lastContentRef.get();
        // 内容和上次完全相同，说明这是重复事件，没有新增内容。
        if (currentContent.equals(lastContent)) {
            // 返回空串，避免前端重复显示同一段话。
            return "";
        }
        // 正常的累计增长情况：本次文本以上次内容开头，说明只是在末尾续写。
        if (lastContent != null && !lastContent.isBlank() && currentContent.startsWith(lastContent)) {
            // 把已推送记录推进到最新的累计文本，下次继续以它为基准比对。
            lastContentRef.set(currentContent);
            // 只返回末尾新长出来的那一段，前端追加显示即可。
            return currentContent.substring(lastContent.length());
        }
        // 走到这里说明本次文本不是上次的延长（例如模型开始了一段全新回答）：
        // 把它接在已推送记录后面，保证下一轮比对时历史不会丢；上次为空则直接以本次为基准。
        lastContentRef.set(lastContent == null ? currentContent : lastContent + currentContent);
        // 整段作为新增内容返回，前端把它接在当前回答后面。
        return currentContent;
    }

    /**
     * 把同步对话收集到的一串「累计文本」合并成一段不重复的完整回答。
     *
     * <p>用于 /chat 接口：那里一次性收齐了所有 ADK 事件，每个事件都是从头开始的累计文本，
     * 若直接拼接，同一句话会出现好几遍。这里复用流式增量算法，按顺序只累加每次新增的部分。</p>
     *
     * <p>数据流：累计文本列表 → 逐条计算增量 → 追加到结果缓冲 → 返回完整回答文本。</p>
     *
     * <p>列表为空（模型没有输出）时返回空串，让上层照常返回一个内容为空的成功响应，
     * 而不是抛异常导致整次对话失败。</p>
     */
    private String mergeAgentContents(List<String> contents) {
        // 模型一句话都没输出，直接给空文本，避免后续拼接逻辑处理空集合。
        if (contents == null || contents.isEmpty()) {
            // 返回空串，上层照常构造成功响应。
            return "";
        }
        // 本地新建一份「已合并内容」的记录，只服务这一次合并，不与任何流共享状态。
        AtomicReference<String> lastContentRef = new AtomicReference<>("");
        // 用缓冲区逐段拼接，避免大量字符串拼接产生额外开销。
        StringBuilder merged = new StringBuilder();
        // 按事件到达顺序逐条处理，顺序不能乱，否则增量计算的基准就错了。
        for (String content : contents) {
            // 复用与流式一致的增量算法：只把新增部分追加进去，重复内容自动被丢弃。
            merged.append(streamDelta(lastContentRef, content));
        }
        // 返回去重后的完整回答文本，与流式推送给前端的内容保持一致。
        return merged.toString();
    }

    /**
     * 判定这次请求真正属于哪个用户。
     *
     * <p>规则：只要认证上下文里有用户（正常的登录请求），就用它，请求体里写谁都无效；
     * 只有认证上下文为空（老接口、内部兼容调用）时，才退回使用请求参数里的 userId。</p>
     *
     * <p>这道判断是防越权的关键：会话、消息、引用的读写都以返回值为准，
     * 若直接信任请求体，任何人都能填别人的 userId 去读写他人的对话。</p>
     */
    private String trustedUserId(String requestUserId) {
        // 先看认证上下文里有没有已登录用户，这是唯一可信来源。
        String userId = TenantContextHolder.getUserId();
        // 有登录用户就用它；确实没有（旧兼容场景）才退回请求里传来的编号。
        return userId == null || userId.isBlank() ? requestUserId : userId;
    }

    /**
     * 判断这次对话请求该走数据库配置的工作流，还是走单个 Agent。
     *
     * <p>判定依据只有一个：workflowId 是否有值。这个结论决定后续用哪套建会话和执行方法，
     * 因此判空写得比较严（空对象、null、空白串都算没有工作流），避免前端传了空字符串就误入工作流分支。</p>
     */
    private boolean hasWorkflow(ChatRequestDTO requestDTO) {
        // 三重判空后才认为要走工作流，任何一项不满足都按普通 Agent 处理。
        return requestDTO != null && requestDTO.getWorkflowId() != null && !requestDTO.getWorkflowId().isBlank();
    }

    /**
     * 判断建会话请求该建工作流会话还是普通 Agent 会话。
     *
     * <p>与对话请求使用同一套判定口径，保证「建会话」和「发消息」两步走的是同一条分支，
     * 不会出现建的是 Agent 会话却按工作流执行这种错配。</p>
     */
    private boolean hasWorkflow(CreateSessionRequestDTO requestDTO) {
        // 同样要求 workflowId 真实存在且非空白，才绑定工作流版本和模型。
        return requestDTO != null && requestDTO.getWorkflowId() != null && !requestDTO.getWorkflowId().isBlank();
    }

    /**
     * 释放流式订阅，让上游停止继续生成内容。
     *
     * <p>被取消、超时、连接出错和推送失败四条路径都会调到这里，所以必须能被重复调用：
     * 句柄为空（还没订阅）或已经释放过，都安静跳过，不抛异常。</p>
     *
     * <p>释放之后模型调用和工作流节点执行会随之中断，不再产生费用和无人接收的输出。</p>
     */
    private void dispose(AtomicReference<Disposable> disposableRef) {
        // 取出当前订阅句柄；如果订阅还没建立，这里会是空值。
        Disposable disposable = disposableRef.get();
        // 只有句柄存在且尚未释放时才动手，保证重复调用是安全的。
        if (disposable != null && !disposable.isDisposed()) {
            // 真正断开订阅，上游随之停止生成内容。
            disposable.dispose();
        }
    }

}
