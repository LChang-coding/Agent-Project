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
 * 租户定时任务配置和执行记录入口。
 * <p>只接受当前 JWT 所代表的执行身份；Cron 落表、到期认领和实际 Agent 调用由领域调度链处理。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleConfigurationService service;
    private final ObjectMapper objectMapper;

    /**
     * 创建定时配置。
     * <p>主动清空客户端 configId，防止创建接口被利用为覆盖更新。</p>
     */
    @PostMapping
    public Response<ScheduleResponseDTO> create(@RequestBody ScheduleSaveRequestDTO request) {
        request.setConfigId(null);
        return save(request);
    }

    /** 使用路径中的 configId 更新现有定时配置。 */
    @PutMapping("/{configId}")
    public Response<ScheduleResponseDTO> update(@PathVariable String configId,
                                                @RequestBody ScheduleSaveRequestDTO request) {
        request.setConfigId(configId);
        return save(request);
    }

    /** 查询当前用户在租户内拥有的定时配置。 */
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

    /** 启用定时配置，后续对账会重新生成待执行任务。 */
    @PostMapping("/{configId}/enable")
    public Response<ScheduleResponseDTO> enable(@PathVariable String configId) {
        return setEnabled(configId, true);
    }

    /** 禁用定时配置，阻止后续任务继续被调度。 */
    @PostMapping("/{configId}/disable")
    public Response<ScheduleResponseDTO> disable(@PathVariable String configId) {
        return setEnabled(configId, false);
    }

    /**
     * 立即触发一次配置。
     * <p>接口只登记即时任务，不在 HTTP 线程中直接运行 Agent。</p>
     */
    @PostMapping("/{configId}/trigger")
    public Response<Void> trigger(@PathVariable String configId) {
        try {
            // 领域服务校验配置归属，并使用数据库幂等键创建即时任务。
            service.triggerNow(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(), configId);
            return success(null);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("手动触发定时任务失败 configId:{}", configId, e);
            return systemFail();
        }
    }

    /** 将人工重试映射为同一条即时触发链，避免复制任务创建逻辑。 */
    @PostMapping("/{configId}/retry")
    public Response<Void> retry(@PathVariable String configId) {
        return trigger(configId);
    }

    /** 查询配置近期执行记录，用 traceId 关联具体 Agent 链路。 */
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

    /** 预览 Cron 的下一组执行时间，不保存任何配置。 */
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

    /**
     * 把 Web 请求转换为调度领域配置并统一执行创建或更新。
     */
    private Response<ScheduleResponseDTO> save(ScheduleSaveRequestDTO request) {
        try {
            // 任务载荷以 JSON 保存，为后续扩展参数保留向后兼容空间。
            String payload = objectMapper.createObjectNode().put("message", request.getMessage()).toString();
            // runAs 身份固定为当前 JWT 用户，禁止浏览器指定更高权限执行身份。
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

    /** 切换配置状态并返回数据库最终版本。 */
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

    /** 将调度配置领域实体转换为前端可编辑结构。 */
    private ScheduleResponseDTO toResponse(ScheduleConfigEntity entity) {
        return ScheduleResponseDTO.builder().configId(entity.getConfigId()).agentId(entity.getAgentId())
                .agentName(entity.getAgentName()).message(readMessage(entity.getTaskPayload()))
                .cronExpr(entity.getCronExpr()).timezone(entity.getTimezone()).enabled(entity.isEnabled())
                .status(entity.getStatus()).misfirePolicy(entity.getMisfirePolicy()).maxRetries(entity.getMaxRetries())
                .configVersion(entity.getConfigVersion()).lastReconciledAt(entity.getLastReconciledAt())
                .createTime(entity.getCreateTime()).updateTime(entity.getUpdateTime()).build();
    }

    /** 将执行账本转换为可观测历史记录。 */
    private ScheduleExecutionResponseDTO toExecution(ScheduleExecutionEntity entity) {
        return ScheduleExecutionResponseDTO.builder().executionId(entity.getExecutionId()).traceId(entity.getTraceId())
                .plannedTime(entity.getPlannedTime()).attemptNo(entity.getAttemptNo()).status(entity.getStatus())
                .startTime(entity.getStartTime()).endTime(entity.getEndTime()).durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage()).build();
    }

    /**
     * 从兼容 JSON 载荷中读取用户消息。
     * <p>旧数据损坏时返回空文本，避免只读列表因单条历史载荷失败。</p>
     */
    private String readMessage(String payload) {
        try {
            return objectMapper.readTree(payload).path("message").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 将领域异常映射为稳定业务错误。 */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /** 将未分类异常收敛为统一系统错误。 */
    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
