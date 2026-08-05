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
     * 把前端请求翻成调度领域配置，并统一执行创建或更新。
     *
     * <p>各层职责：
     * 第一层：把用户消息包成固定结构的 JSON 载荷，为将来扩展参数留出兼容空间。
     * 第二层：把执行身份写死成当前登录用户和角色，杜绝用低权限账号创建一个「以管理员身份运行」的任务。
     * 第三层：交给领域层校验 Cron、时区、载荷、错过策略和重试上限，落库并立刻收敛运行态。
     * 第四层：把落库结果翻成前端可编辑结构；两类异常分别翻译成业务错误码和系统错误码。</p>
     *
     * <p>数据流：
     * 保存请求
     * → 用户消息包成 JSON 载荷
     * → 拼装配置实体（可信租户 / 所有者 / 执行身份 + Cron + 时区 + 错过策略 + 重试上限）
     * → 领域层校验并落库
     * → 立刻收敛成运行态并算出下次触发时间
     * → 回读落库结果
     * → 翻成前端可编辑结构返回</p>
     *
     * <p>会写数据库、会改变后续自动执行的行为。这里对两个可选字段做了兜底：enabled 不填按启用处理
     * （用户新建任务的意图通常就是要它跑），maxRetries 不填按 3 次（能扛住偶发抖动又不至于无限重试）。
     * 主要失败情形：Cron 或时区非法、消息为空或超过 2 万字、错过策略不受支持、指定的配置不属于当前用户。</p>
     */
    private Response<ScheduleResponseDTO> save(ScheduleSaveRequestDTO request) {
        // 校验、落库、收敛任何一步失败都必须转成统一响应，不能把异常抛给前端。
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
            // 落库成功，把数据库里的最终结果（含服务端生成的编号和收敛时间）翻成前端可编辑结构返回。
            return success(toResponse(result));
        } catch (AppException e) {
            // Cron 非法、载荷不合规、无权修改等可预期拒绝，原样返回业务错误码。
            return fail(e);
        } catch (Exception e) {
            // 未知故障，细节只留在日志里。
            log.error("保存定时任务失败", e);
            // 对外统一成系统错误码，前端提示稍后重试。
            return systemFail();
        }
    }

    /**
     * 切换配置的启停状态，并返回数据库里的最终结果。
     *
     * <p>为什么要回读而不是直接返回请求值：领域层改完状态还会重新收敛运行态、算出新的下次触发时间，
     * 只有回读才能把这些派生结果一并给前端，否则界面上的「下次执行时间」会是过期的。</p>
     *
     * <p>会写数据库。配置不存在或不属于当前用户时返回业务错误码。</p>
     */
    private Response<ScheduleResponseDTO> setEnabled(String configId, boolean enabled) {
        // 归属校验失败和未知故障都要收敛成统一响应。
        try {
            // 用可信身份切换状态；领域层顺带重新收敛运行态，返回的实体已包含新的下次触发时间。
            return success(toResponse(service.setEnabled(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), configId, enabled)));
        } catch (AppException e) {
            // 配置不存在或无权操作，原样返回业务错误码。
            return fail(e);
        } catch (Exception e) {
            // 未知故障，保留 configId 便于定位。
            log.error("切换定时任务状态失败 configId:{}", configId, e);
            // 对外统一成系统错误码。
            return systemFail();
        }
    }

    /**
     * 把调度配置实体翻成前端可编辑结构。
     *
     * <p>关键一步是把数据库里的 JSON 载荷还原成一行消息文本，让前端表单能直接编辑；
     * 另外带上 configVersion 和 lastReconciledAt，前端据此判断这份配置有没有真正被收敛生效。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private ScheduleResponseDTO toResponse(ScheduleConfigEntity entity) {
        // 逐字段搬运：配置与 Agent 身份、还原后的消息文本、Cron 与时区、启停与状态、
         // 错过策略与重试上限、配置版本与最近收敛时间，最后是创建和更新时间。
        return ScheduleResponseDTO.builder().configId(entity.getConfigId()).agentId(entity.getAgentId())
                .agentName(entity.getAgentName()).message(readMessage(entity.getTaskPayload()))
                .cronExpr(entity.getCronExpr()).timezone(entity.getTimezone()).enabled(entity.isEnabled())
                .status(entity.getStatus()).misfirePolicy(entity.getMisfirePolicy()).maxRetries(entity.getMaxRetries())
                .configVersion(entity.getConfigVersion()).lastReconciledAt(entity.getLastReconciledAt())
                .createTime(entity.getCreateTime()).updateTime(entity.getUpdateTime()).build();
    }

    /**
     * 把执行账本记录翻成可观测的历史条目。
     *
     * <p>plannedTime 是「本该什么时候跑」，startTime 是「实际什么时候开始跑」，差得多说明调度延迟了；
     * attemptNo 说明这是第几次尝试；traceId 用来把这条记录和详细日志对上。
     * 定时任务没有人在旁边看着，排查全靠这几个字段。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private ScheduleExecutionResponseDTO toExecution(ScheduleExecutionEntity entity) {
        // 逐字段搬运：执行编号与链路标识、计划时间与尝试序号、状态、起止时间与耗时，最后是失败原因。
        return ScheduleExecutionResponseDTO.builder().executionId(entity.getExecutionId()).traceId(entity.getTraceId())
                .plannedTime(entity.getPlannedTime()).attemptNo(entity.getAttemptNo()).status(entity.getStatus())
                .startTime(entity.getStartTime()).endTime(entity.getEndTime()).durationMs(entity.getDurationMs())
                .errorMessage(entity.getErrorMessage()).build();
    }

    /**
     * 从任务载荷 JSON 里取出用户消息。
     *
     * <p>为什么要吞掉异常：这只是给列表展示用的。历史数据里可能存在格式不合的老载荷，
     * 如果让它抛异常，整个列表接口都会失败——用户连别的任务都看不到了。
     * 所以解析不了就返回空文本，只是这一条显示为空，其余照常展示。</p>
     */
    private String readMessage(String payload) {
        // 老数据可能不是合法 JSON，解析失败不能让整个只读列表跟着失败。
        try {
            // 取出 message 字段；字段缺失时返回空串而不是 null，前端表单不必额外判空。
            return objectMapper.readTree(payload).path("message").asText("");
        } catch (Exception ignored) {
            // 载荷损坏时以空文本兜底，只影响这一条的显示，不影响列表整体可用。
            return "";
        }
    }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 业务数据，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 把领域层抛出的业务异常原样翻译成响应：错误码和文案都是设计好的，可直接展示给用户，不带 data。 */
    private <T> Response<T> fail(AppException e) {
        // 只回错误码和文案，前端据此提示具体原因。
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /** 把未预料的故障统一收敛成系统错误码，绝不把内部异常信息推给浏览器。 */
    private <T> Response<T> systemFail() {
        // 只回通用错误码和文案，具体原因已经记在日志里，靠日志排查。
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
