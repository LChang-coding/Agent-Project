package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.AiAgentConfigResponseDTO;
import cn.bugstack.ai.api.dto.agent.AgentStatusUpdateRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 静态 Agent 的租户级启停入口。
 * <p>只转换 HTTP 参数和响应；状态权限、乐观锁及可用性规则由领域服务统一处理。</p>
 */
@RestController
@RequestMapping("/api/v1/agent-configs")
public class AgentConfigController {
    private final AgentAvailabilityService service;

    /**
     * @param service Agent 租户可用性领域服务
     */
    public AgentConfigController(AgentAvailabilityService service) { this.service = service; }

    /**
     * 查询当前租户可见的静态 Agent。
     *
     * @param includeDisabled 是否同时返回已禁用 Agent
     * @return 带租户状态和管理权限标识的 Agent 列表
     */
    @GetMapping
    public Response<List<AiAgentConfigResponseDTO>> list(
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        try {
            // tenantId 只取认证上下文，禁止客户端通过参数跨租户读取配置。
            return success(service.queryConfigs(TenantContextHolder.getTenantId(), includeDisabled).stream()
                    .map(this::toResponse).toList());
        } catch (AppException e) { return failure(e); }
    }

    /**
     * 更新一个静态 Agent 在当前租户下的启停状态。
     *
     * @param agentId 静态配置中的 Agent ID
     * @param request 状态、原因和期望版本；兼容 enabled/status 与 revision/expectedRevision 两组字段
     * @return 更新后的租户状态
     */
    @PutMapping("/{agentId}/status")
    public Response<AiAgentConfigResponseDTO> update(@PathVariable String agentId,
                                                      @RequestBody AgentStatusUpdateRequestDTO request) {
        try {
            // 优先使用布尔 enabled，兼容旧客户端继续提交字符串 status。
            String status = request != null && request.getEnabled() != null
                    ? (request.getEnabled() ? "enabled" : "disabled") : request == null ? null : request.getStatus();
            // expectedRevision 是新协议字段，revision 仅用于旧协议兼容；领域层执行乐观锁校验。
            Long expectedRevision = request != null && request.getExpectedRevision() != null
                    ? request.getExpectedRevision() : request == null ? null : request.getRevision();
            service.updateStatus(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), agentId,
                    status, request == null ? null : request.getReason(), expectedRevision);
            // 更新成功后重新读取事实状态，避免根据请求值猜测数据库最终结果。
            return success(service.queryConfigs(TenantContextHolder.getTenantId(), true).stream()
                    .filter(item -> agentId.equals(item.getAgentId())).findFirst().map(this::toResponse).orElseThrow());
        } catch (AppException e) { return failure(e); }
    }

    /**
     * 在当前租户下“删除”静态 Agent。
     * <p>静态配置不可物理删除，因此 HTTP DELETE 被转换为带版本保护的禁用操作。</p>
     *
     * @param agentId 静态 Agent ID
     * @param revision 客户端读取到的租户状态版本
     * @param reason 禁用原因
     * @return 禁用后的租户状态
     */
    @DeleteMapping("/{agentId}")
    public Response<AiAgentConfigResponseDTO> delete(@PathVariable String agentId,
                                                      @RequestParam(value = "revision", required = false) Long revision,
                                                      @RequestParam(value = "reason", required = false) String reason) {
        // 复用统一更新入口，确保权限、乐观锁和响应结构与显式禁用保持一致。
        AgentStatusUpdateRequestDTO request = new AgentStatusUpdateRequestDTO();
        request.setStatus("disabled"); request.setRevision(revision); request.setReason(reason);
        return update(agentId, request);
    }

    /**
     * 将领域状态转换为公开响应，并按当前角色计算管理能力。
     */
    private AiAgentConfigResponseDTO toResponse(AgentConfigStatusEntity value) {
        AiAgentConfigResponseDTO dto = new AiAgentConfigResponseDTO();
        dto.setAgentId(value.getAgentId()); dto.setAgentName(value.getAgentName()); dto.setAgentDesc(value.getAgentDesc());
        dto.setStatus(value.getStatus()); dto.setEnabled(value.getEnabled()); dto.setRevision(value.getRevision());
        dto.setSourceType("static_config");
        dto.setManageable("owner".equalsIgnoreCase(TenantContextHolder.getRoleCode())
                || "admin".equalsIgnoreCase(TenantContextHolder.getRoleCode()));
        dto.setDisabledAt(value.getDisabledAt() == null ? null : value.getDisabledAt().toString());
        return dto;
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) { return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build(); }

    /** 将可预期业务异常映射为原业务错误码。 */
    private <T> Response<T> failure(AppException e) { return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build(); }
}
