package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.CancelRunRequestDTO;
import cn.bugstack.ai.api.dto.RunControlResponseDTO;
import cn.bugstack.ai.api.dto.SteerRunRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话运行控制接口。
 * <p>负责接收取消和后续引导请求，身份只取可信租户上下文。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/runs")
public class RunControlController {

    private final RunControlService runControlService;

    /**
     * @param runControlService 负责运行状态机、消息失效和压缩任务回滚的领域服务
     */
    public RunControlController(RunControlService runControlService) {
        this.runControlService = runControlService;
    }

    /**
     * 取消当前用户拥有的运行。
     *
     * @param runId 待取消运行ID
     * @param request 可选取消原因
     * @return 取消后的运行状态和上下文版本
     */
    @PostMapping("/{runId}/cancel")
    public Response<RunControlResponseDTO> cancel(@PathVariable String runId,
                                                  @RequestBody(required = false) CancelRunRequestDTO request) {
        try {
            // 身份只取服务端认证上下文，运行归属和可取消状态由领域服务原子校验。
            ChatRunEntity run = runControlService.cancel(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), runId, request == null ? null : request.getReason());
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
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 为正在执行的运行创建后继引导运行。
     *
     * @param runId 当前运行ID
     * @param request 用户追加的引导指令
     * @return 已建立前驱关系的后继运行
     */
    @PostMapping("/{runId}/steer")
    public Response<RunControlResponseDTO> steer(@PathVariable String runId,
                                                 @RequestBody SteerRunRequestDTO request) {
        try {
            // 领域服务决定当前运行能否被引导，并负责冻结前驱与建立后继关系。
            ChatRunEntity successor = runControlService.steer(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), runId, request == null ? null : request.getInstruction());
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponse(successor)).build();
        } catch (AppException e) {
            return Response.<RunControlResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("引导会话运行失败 runId:{}", runId, e);
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 暴露前端继续轮询和串联后继运行所需的最小状态。
     */
    private RunControlResponseDTO toResponse(ChatRunEntity run) {
        return RunControlResponseDTO.builder()
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .status(run.getStatus().name().toLowerCase())
                .contextRevision(run.getCurrentContextRevision())
                .successorRunId(run.getSuccessorRunId())
                .build();
    }
}
