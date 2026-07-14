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
     * 创建运行控制接口；参数是运行控制服务；返回接口实例。
     */
    public RunControlController(RunControlService runControlService) {
        this.runControlService = runControlService;
    }

    /**
     * 取消运行；参数是运行ID和取消原因；返回运行终态。
     */
    @PostMapping("/{runId}/cancel")
    public Response<RunControlResponseDTO> cancel(@PathVariable String runId,
                                                  @RequestBody(required = false) CancelRunRequestDTO request) {
        try {
            ChatRunEntity run = runControlService.cancel(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), runId, request == null ? null : request.getReason());
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponse(run))
                    .build();
        } catch (AppException e) {
            return Response.<RunControlResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("取消会话运行失败 runId:{}", runId, e);
            return Response.<RunControlResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /**
     * 引导运行；参数是运行ID和引导指令；返回待启动后继运行。
     */
    @PostMapping("/{runId}/steer")
    public Response<RunControlResponseDTO> steer(@PathVariable String runId,
                                                 @RequestBody SteerRunRequestDTO request) {
        try {
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
