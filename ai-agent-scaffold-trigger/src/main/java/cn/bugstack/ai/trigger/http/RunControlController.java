package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.CancelRunRequestDTO;
import cn.bugstack.ai.api.dto.RunControlResponseDTO;
import cn.bugstack.ai.api.dto.SteerRunRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.workflow.service.IntelligentWorkflowRuntimeService;
import cn.bugstack.ai.domain.workflow.service.WorkflowRunFinalizationService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对一次正在进行的运行做「叫停」和「插话」两件事的 HTTP 入口。
 *
 * <p>解决什么问题：模型答偏了或者答太久，用户要么点停止，要么想补一句「换个思路再说」。
 * 前者是取消，后者叫引导（steer）——引导会让当前这轮作废并另开一轮后继运行接着答。
 * 这两个动作都会改动已经落库的消息和上下文版本，所以必须走同一个受控入口。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端对话界面的「停止」和「补充指令」按钮，通过 /api/v1/runs 调用。</p>
 *
 * <p>它向下调用什么：
 * 1) {@code RunControlService}：在事务里推进运行状态机、把这轮消息置为失效、回滚压缩与引用派生数据；
 * 2) {@code WorkflowRunFinalizationService}：给普通 DAG 补一条唯一的取消终态事件；
 * 3) {@code IntelligentWorkflowRuntimeService}：收口智能工作流的节点状态并补发取消事件。</p>
 *
 * <p>它不负责什么：不判断运行能不能取消、不加锁、不写消息、不管幂等、不中断后台线程。
 * 这里只做两件事：从认证上下文取可信身份，把领域异常翻译成统一响应码。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/runs")
public class RunControlController {

    /**
     * 运行控制领域服务，取消和引导的核心逻辑都在它里面。
     *
     * <p>它在一个事务里完成：锁会话与运行、推进状态机、把这轮产生的消息标为失效、
     * 递增会话上下文版本、取消模型用量的在跑记录，最后在事务提交后才去中断本机的流。
     * 取消是幂等的——已经是终态的运行再取消一次会原样返回，不会重复失效消息。
     * final 且构造注入，并发请求共享同一实例。</p>
     */
    private final RunControlService runControlService;
    /**
     * 智能工作流运行时服务，这里只用它的取消收口能力。
     *
     * <p>取消请求返回前必须让智能工作流的节点状态也收口：把还在跑的节点标为已取消，
     * 并补发一条 WORKFLOW_CANCELLED 事件。它可以重复调用，已经终态的运行不会重复写事件。</p>
     */
    private final IntelligentWorkflowRuntimeService intelligentWorkflowRuntimeService;
    /**
     * 普通 DAG 工作流的终态收口服务。
     *
     * <p>普通 DAG 的取消可能发生在后台线程还没真正跑起来的瞬间，那时没人负责发终态事件，
     * 正在看 SSE 的前端就会一直等。它的作用就是补上这条唯一的取消终态事件，让事件流能正常结束。</p>
     */
    private final WorkflowRunFinalizationService workflowRunFinalizationService;

    /**
     * 启动时由 Spring 注入三个领域服务：一个改运行状态，两个负责把两种工作流的终态事件补齐。
     *
     * @param runControlService 负责运行状态机、消息失效和压缩任务回滚的领域服务
     * @param intelligentWorkflowRuntimeService 智能工作流取消收口服务
     * @param workflowRunFinalizationService 普通 DAG 取消终态事件补发服务
     */
    public RunControlController(RunControlService runControlService,
                                IntelligentWorkflowRuntimeService intelligentWorkflowRuntimeService,
                                WorkflowRunFinalizationService workflowRunFinalizationService) {
        // 保存运行控制服务引用，取消和引导都要用它。
        this.runControlService = runControlService;
        // 保存智能工作流运行时服务引用，仅用于取消后的状态收口。
        this.intelligentWorkflowRuntimeService = intelligentWorkflowRuntimeService;
        // 保存普通 DAG 终态服务引用，仅用于取消后补发终态事件。
        this.workflowRunFinalizationService = workflowRunFinalizationService;
    }

    /**
     * 取消一次属于当前用户的运行。
     *
     * <p>各层职责：
     * 第一层：只取认证上下文里的身份，交给领域层在事务内校验归属和可取消状态。
     * 第二层：领域层推进状态机、失效这轮消息、递增上下文版本，并在提交后中断本机的流。
     * 第三层：给两种工作流分别补一次终态收口，保证正在看事件流的前端一定能收到结束信号。
     * 第四层：把结果裁剪成前端继续轮询和串联后继运行所需的最小状态。</p>
     *
     * <p>数据流：
     * HTTP 取消请求
     * → 可信身份
     * → 领域层锁会话与运行并推进到 CANCELLED
     * → 这轮消息置为失效、上下文版本 +1
     * → 提交后中断本机 SSE 流
     * → 普通 DAG 补发取消终态事件
     * → 智能工作流收口节点状态并补发取消事件
     * → 返回运行状态与上下文版本</p>
     *
     * <p>会写数据库、会改状态、会发工作流事件。取消是幂等的：重复取消同一个运行不会重复失效消息。
     * 主要失败情形：运行不存在或不属于当前用户、取消过程中状态被并发改写。</p>
     *
     * @param runId 待取消运行ID
     * @param request 可选取消原因
     * @return 取消后的运行状态和上下文版本
     */
    @PostMapping("/{runId}/cancel")
    public Response<RunControlResponseDTO> cancel(@PathVariable String runId,
                                                  @RequestBody(required = false) CancelRunRequestDTO request) {
        // 取消要改状态、失效消息、递增上下文版本，任何一步失败都必须转成统一响应，不能把异常抛给前端。
        try {
            // 身份只取服务端认证上下文，运行归属和可取消状态由领域服务原子校验。
            ChatRunEntity run = runControlService.cancel(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), runId, request == null ? null : request.getReason());
            // 普通 DAG 即使尚未进入后台线程，也必须在取消响应前拥有唯一取消终态事件。
            workflowRunFinalizationService.reconcileCancellation(run);
            // 智能工作流还要额外收口：把仍在执行的节点标为已取消，并补发取消事件，
             // 否则前端事件流会停在最后一个节点上一直转圈。
            intelligentWorkflowRuntimeService.reconcileCancellation(run);
            // 取消成功，把新的运行状态和上下文版本返回；前端据此停止加载动画并刷新这一轮的消息。
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponse(run))
                    .build();
        } catch (AppException e) {
            // 状态冲突、无权限等可预期错误保留领域错误码，便于前端准确提示。
            return Response.<RunControlResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            // 未知异常不向客户端暴露内部原因，但保留 runId 供日志定位。
            log.error("取消会话运行失败 runId:{}", runId, e);
            // 对外统一成系统错误码；此时运行可能已部分推进，前端应重新拉取运行状态确认真实结果。
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 给一次正在执行的运行补一条引导指令，另开一轮后继运行来回答。
     *
     * <p>为什么不是直接把指令追加进去：当前这轮已经基于旧提示词在推理了，直接追加会让上下文自相矛盾。
     * 所以领域层的做法是——把当前运行推进到 STEER_REQUESTED、让它产生的消息全部失效、
     * 再新建一个带前驱关系的后继运行，由前端用返回的 runId 去启动它。</p>
     *
     * <p>数据流：
     * 引导指令
     * → 可信身份
     * → 领域层锁会话与运行
     * → 当前运行标记为已被引导并冻结
     * → 这轮消息失效、上下文版本 +1
     * → 创建带前驱关系的后继运行
     * → 返回后继 runId 给前端启动</p>
     *
     * <p>会写数据库、会改状态。相同指令的重试会返回已有的后继运行（幂等）；
     * 换成不同指令去引导同一个旧运行会被拒绝，避免同一轮分叉出两条回答。
     * 其他失败情形：指令为空或超过 4000 字、运行已经结束因此不能再被引导。</p>
     *
     * @param runId 当前运行ID
     * @param request 用户追加的引导指令
     * @return 已建立前驱关系的后继运行
     */
    @PostMapping("/{runId}/steer")
    public Response<RunControlResponseDTO> steer(@PathVariable String runId,
                                                 @RequestBody SteerRunRequestDTO request) {
        // 引导会失效已有消息并新建运行，属于写操作，异常必须收敛成统一响应。
        try {
            // 领域服务决定当前运行能否被引导，并负责冻结前驱与建立后继关系。
            ChatRunEntity successor = runControlService.steer(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), runId, request == null ? null : request.getInstruction());
            // 把后继运行的编号和上下文版本返回；前端拿它作为 requestedRunId 发起下一轮对话。
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponse(successor)).build();
        } catch (AppException e) {
            // 指令为空、运行已结束、存在不同指令的后继等都是可预期拒绝，原样返回业务错误码供前端提示。
            return Response.<RunControlResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            // 未预期故障，保留 runId 便于按运行追查，具体原因不外泄。
            log.error("引导会话运行失败 runId:{}", runId, e);
            // 统一系统错误码返回；后继运行可能没建成，前端应重新拉取运行状态再决定是否重试。
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 裁剪出前端继续轮询和串联后继运行所需的最小状态。
     *
     * <p>只给这几样：运行和会话编号、小写状态、上下文版本（前端据此判断历史是否被改写）、
     * 后继运行编号（引导场景下前端要用它发起下一轮）、以及两个链路标识——
     * traceId 属于被操作的那次运行，operationTraceId 属于本次控制请求，排查时不能混为一谈。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RunControlResponseDTO toResponse(ChatRunEntity run) {
        // 逐字段搬运运行身份、状态、上下文版本、后继运行以及两个用途不同的链路标识。
        return RunControlResponseDTO.builder()
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .status(run.getStatus().name().toLowerCase())
                .contextRevision(run.getCurrentContextRevision())
                .successorRunId(run.getSuccessorRunId())
                .ragInvocationMode(cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode
                        .resolve(run.getRagInvocationMode()).name())
                .traceId(run.getTraceId())
                .operationTraceId(TraceContext.ensureTraceId())
                .build();
    }
}
