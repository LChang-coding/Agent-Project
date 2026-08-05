package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.workflow.WorkflowRunEventResponseDTO;
import cn.bugstack.ai.api.dto.workflow.IntelligentWorkflowRunStartRequestDTO;
import cn.bugstack.ai.api.dto.workflow.IntelligentWorkflowRunResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRuntimeService;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowStartCommandEntity;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.observability.TraceContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import io.reactivex.rxjava3.disposables.Disposable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能工作流的「启动运行」和「事件续传」两个 HTTP 入口。
 *
 * <p>解决什么问题：智能工作流可能跑很久，中间浏览器刷新、断网、切页面都很常见。如果执行过程绑在 HTTP
 * 连接上，连接一断运行就没了。所以这里把「启动」和「看进度」彻底拆开：启动接口立刻返回 runId，
 * 运行在后台线程继续；看进度是另一条 SSE 连接，按事件序号 afterSequence 从上次断掉的位置接着读。
 * 断开连接只是结束这次订阅，绝不会取消后台运行。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端的智能工作流运行页面，通过 /api/v1/intelligent-workflow-runs 调用。</p>
 *
 * <p>它向下调用什么：
 * 1) {@code IntelligentWorkflowRuntimeService}：校验发布版本、建运行记录、把 DAG 执行提交到后台线程；
 * 2) {@code WorkflowEventStreamService}：校验运行归属，并以数据库事件表为真相源提供可续传的事件流。</p>
 *
 * <p>它不负责什么：不执行任何节点、不调模型、不判断运行能不能取消、不生成事件、不做事件保留期管理。
 * 这里只做三件事：把请求翻成领域命令、先下发一条元数据事件告诉前端从哪个序号开始、把领域事件转成 SSE 推出去。</p>
 */
@RestController
@RequestMapping("/api/v1/intelligent-workflow-runs")
public class IntelligentWorkflowRunController {

    /**
     * 工作流事件流服务，负责「归属校验 + 按序号续传事件」。
     *
     * <p>它以数据库事件表为真相源：本机有新事件时立刻唤醒订阅，同时还会周期轮询，
     * 这样即使运行跑在另一台实例上、或本机唤醒信号丢了，前端照样能读到完整事件序列。
     * final 且构造注入，所有并发的 SSE 连接共享同一实例。</p>
     */
    private final WorkflowEventStreamService eventStreamService;
    /**
     * 智能工作流运行时服务，负责启动一次运行。
     *
     * <p>它在事务提交后才把 DAG 执行提交到后台线程池，因此运行记录一定先落库、再开始跑，
     * 前端拿到 runId 时事件表里已经有 WORKFLOW_STARTED，不会出现「查不到这个运行」的空窗。
     * final 且构造注入，运行期不变。</p>
     */
    private final IntelligentWorkflowRuntimeService runtimeService;

    /**
     * 启动时由 Spring 注入两个领域服务；一个负责启动运行，一个负责读事件，职责不重叠。
     *
     * @param eventStreamService 工作流事件续传服务
     * @param runtimeService 智能工作流运行时服务
     */
    public IntelligentWorkflowRunController(WorkflowEventStreamService eventStreamService,
                                            IntelligentWorkflowRuntimeService runtimeService) {
        // 保存事件流服务引用，供 SSE 续传接口使用。
        this.eventStreamService = eventStreamService;
        // 保存运行时服务引用，供启动接口使用。
        this.runtimeService = runtimeService;
    }

    /**
     * 启动一次智能工作流运行，受理后立刻返回，不等它跑完。
     *
     * <p>为什么要立刻返回：DAG 可能跑几分钟甚至更久，HTTP 请求不该在这里挂着。领域层会在事务提交后
     * 才把执行提交到后台线程池，所以本方法返回时运行记录和第一条事件都已经落库，
     * 前端马上用返回的 runId 去连 SSE 就能读到完整过程。</p>
     *
     * <p>数据流：
     * HTTP 请求
     * → 拼装启动命令（可信租户/用户/角色 + 工作流版本 + 消息 + 附件）
     * → 领域层校验发布版本是智能工作流
     * → 补建会话（前端没给 sessionId 时）
     * → 建运行记录并落库用户消息
     * → 发出 WORKFLOW_STARTED 事件
     * → 事务提交后把 DAG 提交到后台线程
     * → 返回 runId 与两个 traceId 给前端</p>
     *
     * <p>会写数据库、会启动后台执行。主要失败情形：所选发布版本不是智能工作流、角色无权访问该工作流、
     * 运行记录创建冲突；这些都以业务异常形式抛出，由全局异常处理转成错误响应。</p>
     *
     * @param request 工作流、版本、模型、会话、消息、附件和客户端幂等 runId
     * @return 运行编号、当前状态、预算上限以及排查用的两个链路标识
     */
    @PostMapping
    public Response<IntelligentWorkflowRunResponseDTO> start(@RequestBody IntelligentWorkflowRunStartRequestDTO request) {
        // 身份三件套全部取自认证上下文，绝不采信请求体，避免有人用别人的身份跑工作流；
         // 工作流、版本、模型、会话、消息、附件则原样转交，由领域层负责校验和冻结版本。
        IntelligentWorkflowRunEntity run = runtimeService.start(IntelligentWorkflowStartCommandEntity.builder()
                .tenantId(TenantContextHolder.getTenantId()).userId(TenantContextHolder.getUserId())
                .roleCode(TenantContextHolder.getRoleCode()).workflowId(request.getWorkflowId())
                .workflowVersion(request.getWorkflowVersion()).modelCode(request.getModelCode())
                .sessionId(request.getSessionId()).message(request.getMessage()).requestedRunId(request.getRequestedRunId())
                .attachmentIds(request.getAttachmentIds()).build());
        // 组装受理响应：除了运行状态，还同时返回两个链路标识——
         // traceId 是这次运行自己的根链路（后台节点日志都归在它下面），
         // operationTraceId 是本次 HTTP 请求的链路，用户反馈「点了没反应」时查它。
        return Response.<IntelligentWorkflowRunResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(IntelligentWorkflowRunResponseDTO.builder().runId(run.getRunId()).workflowId(run.getWorkflowId())
                        .workflowVersion(run.getWorkflowVersion()).status(run.getStatus()).currentNodeId(run.getCurrentNodeId())
                        .traceId(run.getTraceId()).operationTraceId(TraceContext.ensureTraceId())
                        .maxSteps(run.getMaxSteps()).tokenBudget(run.getTokenBudget()).build()).build();
    }

    /**
     * 建立一条 SSE 连接，把某次运行的事件从指定序号开始持续推给前端。
     *
     * <p>各层职责：
     * 第一层：取可信身份和本次请求的链路标识，并校验这个运行确实属于当前用户，越权直接失败。
     * 第二层：建立 30 分钟超时的 SSE 通道，先推一条元数据事件，告诉前端本次从哪个序号开始接。
     * 第三层：订阅领域事件流并保存订阅句柄；句柄要放进引用盒子，四个生命周期回调才能统一释放它。
     * 第四层：给连接挂上完成、超时、出错三个回调，任何一种结束方式都释放订阅，避免线程和连接泄漏。</p>
     *
     * <p>数据流：
     * runId + afterSequence
     * → 归属校验
     * → 建立 SseEmitter（30 分钟超时）
     * → 推送 STREAM_METADATA 事件（协议版本、runId、两个 traceId、起始序号）
     * → 订阅数据库事件流（本机唤醒 + 周期轮询）
     * → 逐条推送 workflow_event 事件
     * → 收到终态事件后正常关闭
     * → 连接结束时释放订阅</p>
     *
     * <p>不写数据库、不改运行状态。关键约定：连接断开只结束订阅，后台运行照常继续，
     * 前端可以带上最后收到的 sequence 重新连上来接着读。
     * 若请求的历史事件已超出保留期，领域层会抛异常，前端应改为直接读最终快照。</p>
     *
     * @param runId 目标运行编号
     * @param afterSequence 已经收到的最大事件序号，从它之后开始推；首次连接传 0
     * @return SSE 通道；方法返回后事件仍在后台线程持续推送
     */
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(defaultValue = "0") long afterSequence) throws Exception {
        // 租户与用户只认认证上下文，它们同时也是事件查询的隔离条件。
        String tenantId = TenantContextHolder.getTenantId();
        // 取当前登录用户，作为运行归属校验和事件过滤的依据。
        String userId = TenantContextHolder.getUserId();
        // 本次 SSE 请求自己的链路标识，和运行的根链路不是一回事，两个都要给前端便于分别排查。
        String operationTraceId = TraceContext.ensureTraceId();
        // 先确认这个运行存在且属于当前用户；不属于就直接失败，绝不建立连接，避免变成探测他人 runId 的口子。
        IntelligentWorkflowRunEntity run = eventStreamService.requireRun(tenantId, userId, runId);
        // 建立 SSE 通道并给 30 分钟上限：智能工作流可能跑很久，超时后会走 onTimeout 释放订阅。
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        // 第一条必须是元数据事件：告诉前端协议版本、运行的根链路、本次请求链路，以及真正的起始序号
         // （服务端会把负数纠正为 0），前端据此决定断线重连时该传什么 afterSequence。
        emitter.send(SseEmitter.event().name("STREAM_METADATA").data(Map.of(
                "schemaVersion", WorkflowEventStreamService.SCHEMA_VERSION,
                "runId", runId,
                "traceId", run.getTraceId(),
                "operationTraceId", operationTraceId,
                "afterSequence", Math.max(0, afterSequence))));
        // 用引用盒子持有订阅句柄：三个生命周期回调是在 lambda 里注册的，必须通过盒子才能拿到同一个句柄。
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        // 订阅事件流：三个回调分别处理有新事件、流出错、正常结束（收到终态事件后领域层会自动结束流）。
        Disposable disposable = eventStreamService.stream(tenantId, userId, runId, afterSequence).subscribe(
                event -> sendEvent(emitter, event),
                error -> completeError(emitter, error, run.getTraceId()),
                emitter::complete);
        // 把真实句柄放进盒子；此后任意一种连接结束方式都能找到它并释放。
        subscription.set(disposable);
        // 正常结束时释放订阅，避免继续往已关闭的连接推送。
        emitter.onCompletion(() -> dispose(subscription));
        // 超过 30 分钟时释放订阅；注意这只是断开本次观看，后台运行不受影响。
        emitter.onTimeout(() -> dispose(subscription));
        // 客户端关页或网络断开时释放订阅，防止句柄和轮询定时器一直留在内存里。
        emitter.onError(error -> dispose(subscription));
        // 把通道交回 Spring；方法返回不代表推送结束，事件仍在后台线程持续写入这个 emitter。
        return emitter;
    }

    /**
     * 把一条领域事件转成 SSE 消息推给前端。
     *
     * <p>事件 id 用数据库序号：浏览器原生 EventSource 会记住最后一个 id，断线重连时前端可以直接用它当
     * afterSequence，从断点继续，不会漏事件也不会重复。</p>
     *
     * <p>推送失败一般说明客户端已经断开，此时以错误方式结束连接，让上层回调去释放订阅，
     * 不要继续往一个死连接里写数据。</p>
     */
    private void sendEvent(SseEmitter emitter, WorkflowRunEventEntity event) {
        // 推送可能因客户端断开而抛异常，必须接住，否则异常会打断整条订阅链。
        try {
            // 事件序号既当 SSE 的 id（供断线续传），又原样放进负载；再把领域事件逐字段裁剪成对外 DTO，
             // 只给前端渲染节点进度真正需要的字段。
            emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence())).name("workflow_event")
                    .data(WorkflowRunEventResponseDTO.builder().schemaVersion(event.getSchemaVersion())
                            .eventId(event.getEventId()).sequence(event.getSequence()).runId(event.getRunId())
                            .eventType(event.getEventType()).nodeExecutionId(event.getNodeExecutionId())
                            .nodeId(event.getNodeId()).payloadJson(event.getPayloadJson()).traceId(event.getTraceId())
                            .occurredAt(String.valueOf(event.getOccurredAt())).build()));
        // 写不进去说明连接已经没了，没有别的补救手段。
        } catch (Exception exception) {
            // 以错误方式结束连接，触发 onError 回调去释放订阅，尽快收敛这条链路。
            emitter.completeWithError(exception);
        }
    }

    /**
     * 把事件流的异常转成一条 error 事件，并确保连接一定被关闭。
     *
     * <p>区分两类异常：领域层抛出的业务异常带着可展示的错误码（例如「事件历史已过保留期」），
     * 前端据此提示用户改去读最终快照；其他异常只给一个通用错误码。</p>
     *
     * <p>关键点在 finally：即使连 error 事件都发不出去（客户端已断开），也必须关闭连接，
     * 否则这条 SSE 会一直挂着，白占服务端资源，前端也永远等不到结束。</p>
     */
    private void completeError(SseEmitter emitter, Throwable error, String traceId) {
        // 推送错误事件本身也可能失败，所以要接住。
        try {
            // 把错误码、原因和运行的根链路一起给前端：业务异常用它自己的码，其他异常统一成流错误码，
             // 消息为空时给一句兜底文案，不能推一条空错误让前端无从提示。
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "code", error instanceof cn.bugstack.ai.types.exception.AppException app ? app.getCode() : "WORKFLOW_STREAM_ERROR",
                    "message", error.getMessage() == null ? "工作流事件流失败" : error.getMessage(),
                    "traceId", traceId)));
        // 连错误事件都写不出去，说明连接已经断了，不必再做任何补救。
        } catch (Exception ignored) {
            // 连接已关闭时无需再次写入。
        } finally {
            // 无论错误事件是否发送成功，都必须关闭连接，防止流永远停在半开状态。
            emitter.complete();
        }
    }

    /**
     * 释放 SSE 订阅，停止继续读取和推送事件。
     *
     * <p>完成、超时、出错三条路径都会调到这里，所以必须能被重复调用：
     * 用 getAndSet(null) 把句柄取出并清空，保证只有第一次调用拿到真句柄，后面的调用安静跳过。</p>
     *
     * <p>只清理本次连接的进程内资源；不写数据库，也不会取消后台的工作流运行。</p>
     */
    private void dispose(AtomicReference<Disposable> reference) {
        // 取出句柄并同时清空盒子，这一步天然保证了重复调用只有第一次生效。
        Disposable disposable = reference.getAndSet(null);
        // 句柄存在且还没释放过才真正断开订阅，停止轮询数据库事件表。
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
    }
}
