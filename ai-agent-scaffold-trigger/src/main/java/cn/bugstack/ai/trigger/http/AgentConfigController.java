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

/** 静态 Agent 租户状态管理控制器。 */
@RestController
@RequestMapping("/api/v1/agent-configs")
public class AgentConfigController {
    private final AgentAvailabilityService service;
    /** 创建控制器；参数是 Agent 可用性服务；返回控制器实例。 */
    public AgentConfigController(AgentAvailabilityService service) { this.service = service; }

    /** 查询 Agent 配置；参数决定是否包含禁用项；返回租户状态列表。 */
    @GetMapping
    public Response<List<AiAgentConfigResponseDTO>> list(
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        try {
            return success(service.queryConfigs(TenantContextHolder.getTenantId(), includeDisabled).stream()
                    .map(this::toResponse).toList());
        } catch (AppException e) { return failure(e); }
    }

    /** 更新 Agent 状态；参数是 Agent 和状态请求；返回最新状态。 */
    @PutMapping("/{agentId}/status")
    public Response<AiAgentConfigResponseDTO> update(@PathVariable String agentId,
                                                      @RequestBody AgentStatusUpdateRequestDTO request) {
        try {
            String status = request != null && request.getEnabled() != null
                    ? (request.getEnabled() ? "enabled" : "disabled") : request == null ? null : request.getStatus();
            Long expectedRevision = request != null && request.getExpectedRevision() != null
                    ? request.getExpectedRevision() : request == null ? null : request.getRevision();
            service.updateStatus(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), agentId,
                    status, request == null ? null : request.getReason(), expectedRevision);
            return success(service.queryConfigs(TenantContextHolder.getTenantId(), true).stream()
                    .filter(item -> agentId.equals(item.getAgentId())).findFirst().map(this::toResponse).orElseThrow());
        } catch (AppException e) { return failure(e); }
    }

    /** 删除 Agent；参数是 Agent、版本和原因；删除语义映射为租户禁用。 */
    @DeleteMapping("/{agentId}")
    public Response<AiAgentConfigResponseDTO> delete(@PathVariable String agentId,
                                                      @RequestParam(value = "revision", required = false) Long revision,
                                                      @RequestParam(value = "reason", required = false) String reason) {
        AgentStatusUpdateRequestDTO request = new AgentStatusUpdateRequestDTO();
        request.setStatus("disabled"); request.setRevision(revision); request.setReason(reason);
        return update(agentId, request);
    }

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
    private <T> Response<T> success(T data) { return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build(); }
    private <T> Response<T> failure(AppException e) { return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build(); }
}
