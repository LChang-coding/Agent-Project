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
 * 管理「哪些内置智能体在本租户可用」的 HTTP 入口。
 *
 * <p>解决什么问题：内置 Agent 写在静态配置里，所有租户共用同一份定义。管理员需要能在自己租户内单独关掉
 * 某个 Agent，既不影响别的租户，也不能真把静态配置删掉。这个控制器就是那个「租户级开关」的入口。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端管理页面。</p>
 *
 * <p>谁会调用它：Web 管理后台，通过 /api/v1/agent-configs 下的 HTTP 接口调用。</p>
 *
 * <p>它向下调用什么：只调 {@code AgentAvailabilityService}——由它读静态配置、合并租户级启停状态、
 * 做角色权限判断、执行乐观锁更新。</p>
 *
 * <p>它不负责什么：不判断谁有权改状态、不做版本冲突检测、不写数据库、不缓存配置，也不新建或物理删除 Agent
 * 定义。这里只做三件事：从认证上下文取租户与角色、把新老两套请求字段收敛成一套、把领域实体裁剪成对外 DTO。</p>
 */
@RestController
@RequestMapping("/api/v1/agent-configs")
public class AgentConfigController {
    /**
     * Agent 租户可用性领域服务，本控制器唯一的下游依赖。
     *
     * <p>查列表、改启停都走它。租户隔离、角色校验、乐观锁版本比对全部发生在它内部，所以这里拿到的数据
     * 已经是「当前租户、当前角色应该看到的事实」，控制器不必再过滤一遍。
     * 声明为 final 并在构造时注入，运行期不会被替换，多个并发请求共享同一实例是安全的。</p>
     */
    private final AgentAvailabilityService service;

    /**
     * 启动时由 Spring 注入领域服务；注入完成后依赖不再变化，之后所有请求共用这一个引用。
     *
     * @param service Agent 租户可用性领域服务
     */
    public AgentConfigController(AgentAvailabilityService service) { this.service = service; }

    /**
     * 查询当前租户可见的内置 Agent 列表，供管理页面展示和前端下拉选择。
     *
     * <p>默认只返回还能用的；管理页面要展示「已停用」条目时传 includeDisabled=true 才会带上它们。
     * 不写库、不改状态、不发事件。角色不足或租户上下文缺失时领域层抛业务异常，这里翻译成错误码返回。</p>
     *
     * @param includeDisabled 是否同时返回已禁用 Agent
     * @return 带租户状态和管理权限标识的 Agent 列表
     */
    @GetMapping
    public Response<List<AiAgentConfigResponseDTO>> list(
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        // 领域层可能因为角色不足或租户缺失直接拒绝，这里统一接住转成业务错误码，不把异常堆栈抛给前端。
        try {
            // tenantId 只取认证上下文，禁止客户端通过参数跨租户读取配置。
            return success(service.queryConfigs(TenantContextHolder.getTenantId(), includeDisabled).stream()
                    .map(this::toResponse).toList());
        } catch (AppException e) { return failure(e); }
    }

    /**
     * 更新一个内置 Agent 在当前租户下的启停状态。
     *
     * <p>请求体存在两套字段是历史遗留：新客户端传布尔 enabled 与 expectedRevision，老客户端传字符串 status
     * 与 revision。这里先把两套收敛成一套，再交给领域层，避免前端版本差异渗透进领域逻辑。</p>
     *
     * <p>会写数据库：租户级启停状态由领域层落库并推进版本号。
     * 主要失败情形：角色不是 owner/admin、版本号与库里不一致（说明别人刚改过）、agentId 在静态配置里不存在。</p>
     *
     * @param agentId 静态配置中的 Agent ID
     * @param request 状态、原因和期望版本；兼容 enabled/status 与 revision/expectedRevision 两组字段
     * @return 更新后的租户状态
     */
    @PutMapping("/{agentId}/status")
    public Response<AiAgentConfigResponseDTO> update(@PathVariable String agentId,
                                                      @RequestBody AgentStatusUpdateRequestDTO request) {
        // 权限不足和版本冲突都由领域层抛业务异常，这里只负责翻译成响应码，保证前端拿到结构一致的响应。
        try {
            // 优先使用布尔 enabled，兼容旧客户端继续提交字符串 status。
            String status = request != null && request.getEnabled() != null
                    ? (request.getEnabled() ? "enabled" : "disabled") : request == null ? null : request.getStatus();
            // expectedRevision 是新协议字段，revision 仅用于旧协议兼容；领域层执行乐观锁校验。
            Long expectedRevision = request != null && request.getExpectedRevision() != null
                    ? request.getExpectedRevision() : request == null ? null : request.getRevision();
            // 把租户、操作人、角色、目标 Agent、目标状态、原因和期望版本一次性交给领域层落库并推进版本号。
            service.updateStatus(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), agentId,
                    status, request == null ? null : request.getReason(), expectedRevision);
            // 更新成功后重新读取事实状态，避免根据请求值猜测数据库最终结果。
            return success(service.queryConfigs(TenantContextHolder.getTenantId(), true).stream()
                    .filter(item -> agentId.equals(item.getAgentId())).findFirst().map(this::toResponse).orElseThrow());
        } catch (AppException e) { return failure(e); }
    }

    /**
     * 在当前租户下「删除」内置 Agent。
     *
     * <p>静态配置不可物理删除，因此 HTTP DELETE 被转换为带版本保护的禁用操作。
     * 前端看到的效果是「删掉了」，实际库里只是把这个 Agent 在本租户标记为停用，其他租户完全不受影响。</p>
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
        // 把 DELETE 语义翻译成「停用」，并带上客户端版本与原因，供领域层做乐观锁比对和留痕。
        request.setStatus("disabled"); request.setRevision(revision); request.setReason(reason);
        // 转交给 PUT 版本执行，避免删除路径出现一套单独的、容易漏校验的实现。
        return update(agentId, request);
    }

    /**
     * 把领域状态实体转换成对外响应，并顺手算出前端该不该显示启停开关。
     *
     * <p>这是一道边界：领域实体带着内部字段，不能直接序列化出去。这里逐字段挑选前端要用的部分，
     * 并把「当前角色能不能管理」这个结论就地算好，省得前端自己再判断一遍角色。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private AiAgentConfigResponseDTO toResponse(AgentConfigStatusEntity value) {
        // 新建对外响应对象，避免把领域实体直接暴露给调用方。
        AiAgentConfigResponseDTO dto = new AiAgentConfigResponseDTO();
        // 搬运身份与展示信息：编号供前端后续调用启停接口，名称和描述供界面展示。
        dto.setAgentId(value.getAgentId()); dto.setAgentName(value.getAgentName()); dto.setAgentDesc(value.getAgentDesc());
        // 搬运启停事实与版本号；revision 必须回给前端，下次改状态要原样带回来做乐观锁。
        dto.setStatus(value.getStatus()); dto.setEnabled(value.getEnabled()); dto.setRevision(value.getRevision());
        // 标明这条记录来自静态配置而非用户自建，前端据此隐藏「编辑定义」「彻底删除」之类的入口。
        dto.setSourceType("static_config");
        // 只有租户 owner 或 admin 才允许改启停，这里提前算好结论，前端直接按它决定按钮是否可点。
        dto.setManageable("owner".equalsIgnoreCase(TenantContextHolder.getRoleCode())
                || "admin".equalsIgnoreCase(TenantContextHolder.getRoleCode()));
        // 停用时间转成字符串返回；从未停用过就是空值，前端据此显示「停用于某时」。
        dto.setDisabledAt(value.getDisabledAt() == null ? null : value.getDisabledAt().toString());
        // 交回裁剪后的对外对象，参与上层的列表收集或单条响应。
        return dto;
    }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) { return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build(); }

    /** 把领域层抛出的业务异常原样翻译成响应：错误码和文案都是设计好的，可直接展示给用户，不带 data。 */
    private <T> Response<T> failure(AppException e) { return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build(); }
}
