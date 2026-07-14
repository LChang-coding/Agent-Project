package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.schedule.ScheduleExecutionResponseDTO;
import cn.bugstack.ai.api.dto.schedule.ScheduleResponseDTO;
import cn.bugstack.ai.api.dto.schedule.ScheduleSaveRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;
import cn.bugstack.ai.domain.schedule.service.ScheduleConfigurationService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务管理接口，只接受当前 JWT 所代表的执行身份。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleConfigurationService service;
    private final ObjectMapper objectMapper;

    @PostMapping
    public Response<ScheduleResponseDTO> create(@RequestBody ScheduleSaveRequestDTO request) {
        request.setConfigId(null);
        return save(request);
    }

    @PutMapping("/{configId}")
    public Response<ScheduleResponseDTO> update(@PathVariable String configId,
                                                @RequestBody ScheduleSaveRequestDTO request) {
        request.setConfigId(configId);
        return save(request);
    }

    @GetMapping
    public Response<List<ScheduleResponseDTO>> list() {
        try {
            return success(service.list(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId())
                    .stream().map(this::toResponse).toList());
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询定时任务失败", e);
            return systemFail();
        }
    }

    @PostMapping("/{configId}/enable")
    public Response<ScheduleResponseDTO> enable(@PathVariable String configId) {
        return setEnabled(configId, true);
    }

    @PostMapping("/{configId}/disable")
    public Response<ScheduleResponseDTO> disable(@PathVariable String configId) {
        return setEnabled(configId, false);
    }

    @PostMapping("/{configId}/trigger")
    public Response<Void> trigger(@PathVariable String configId) {
        try {
            service.triggerNow(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(), configId);
            return success(null);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("手动触发定时任务失败 configId:{}", configId, e);
            return systemFail();
        }
    }

    @PostMapping("/{configId}/retry")
    public Response<Void> retry(@PathVariable String configId) {
        return trigger(configId);
    }

    @GetMapping("/{configId}/executions")
    public Response<List<ScheduleExecutionResponseDTO>> executions(@PathVariable String configId,
                                                                   @RequestParam(defaultValue = "50") int limit) {
        try {
            return success(service.executions(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    configId, limit).stream().map(this::toExecution).toList());
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询定时任务历史失败 configId:{}", configId, e);
            return systemFail();
        }
    }

    @GetMapping("/cron-preview")
    public Response<List<LocalDateTime>> preview(@RequestParam String cron,
                                                 @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
                                                 @RequestParam(defaultValue = "5") int count) {
        try {
            return success(service.preview(cron, timezone, count));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("预览 Cron 失败", e);
            return systemFail();
        }
    }

    private Response<ScheduleResponseDTO> save(ScheduleSaveRequestDTO request) {
        try {
            String payload = objectMapper.createObjectNode().put("message", request.getMessage()).toString();
            ScheduleConfigEntity result = service.save(ScheduleConfigEntity.builder()
                    .tenantId(TenantContextHolder.getTenantId()).ownerUserId(TenantContextHolder.getUserId())
                    .runAsUserId(TenantContextHolder.getUserId()).runAsRoleCode(TenantContextHolder.getRoleCode())
                    .configId(request.getConfigId()).agentId(request.getAgentId()).agentName(request.getAgentName())
                    .taskPayload(payload).cronExpr(request.getCronExpr()).timezone(request.getTimezone())
                    .enabled(request.getEnabled() == null || request.getEnabled())
                    .misfirePolicy(request.getMisfirePolicy())
                    .maxRetries(request.getMaxRetries() == null ? 3 : request.getMaxRetries()).build());
            return success(toResponse(result));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("保存定时任务失败", e);
            return systemFail();
        }
    }

    private Response<ScheduleResponseDTO> setEnabled(String configId, boolean enabled) {
        try {
            return success(toResponse(service.setEnabled(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), configId, enabled)));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("切换定时任务状态失败 configId:{}", configId, e);
            return systemFail();
        }
    }

    private ScheduleResponseDTO toResponse(ScheduleConfigEntity entity) {
        return ScheduleResponseDTO.builder().configId(entity.getConfigId()).agentId(entity.getAgentId())
                .agentName(entity.getAgentName()).message(readMessage(entity.getTaskPayload()))
                .cronExpr(entity.getCronExpr()).timezone(entity.getTimezone()).enabled(entity.isEnabled())
                .status(entity.getStatus()).misfirePolicy(entity.getMisfirePolicy()).maxRetries(entity.getMaxRetries())
                .configVersion(entity.getConfigVersion()).lastReconciledAt(entity.getLastReconciledAt())
                .createTime(entity.getCreateTime()).updateTime(entity.getUpdateTime()).build();
    }

    private ScheduleExecutionResponseDTO toExecution(ScheduleExecutionEntity entity) {
        return ScheduleExecutionResponseDTO.builder().executionId(entity.getExecutionId()).traceId(entity.getTraceId())
                .plannedTime(entity.getPlannedTime()).attemptNo(entity.getAttemptNo()).status(entity.getStatus())
                .startTime(entity.getStartTime()).endTime(entity.getEndTime()).durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage()).build();
    }

    private String readMessage(String payload) {
        try {
            return objectMapper.readTree(payload).path("message").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
