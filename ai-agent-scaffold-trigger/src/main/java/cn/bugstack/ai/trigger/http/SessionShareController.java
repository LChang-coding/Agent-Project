package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.share.CreateSessionShareRequestDTO;
import cn.bugstack.ai.api.dto.share.ImportSessionShareRequestDTO;
import cn.bugstack.ai.api.dto.share.SessionShareResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.share.model.SessionShareResultEntity;
import cn.bugstack.ai.domain.share.model.SessionToolAccessEntity;
import cn.bugstack.ai.domain.share.model.SessionToolDependencyEntity;
import cn.bugstack.ai.domain.share.service.SessionShareService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 会话快照分享、下载、工具权限预检和复制导入入口。
 * <p>分享接收者获得独立会话副本，不继承来源用户身份、工具授权和正在运行的任务。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session-shares")
public class SessionShareController {

    /** 创建、校验、下载、导入和撤销会话分享快照。 */
    private final SessionShareService shareService;

    /** @param shareService 会话分享快照和导入领域服务 */
    public SessionShareController(SessionShareService shareService) {
        this.shareService = shareService;
    }

    /**
     * 为本人会话创建限时、限下载次数的分享。
     */
    @PostMapping
    public Response<SessionShareResponseDTO> create(@RequestBody CreateSessionShareRequestDTO request) {
        try {
            // 领域服务冻结有效消息和工具依赖，后续来源会话变化不污染已生成快照。
            SessionShareResultEntity result = shareService.create(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), request.getSessionId(), request.getValidHours(),
                    request.getMaxDownloads());
            return success(toResponse(result, result.getToken()));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("创建会话分享失败", e);
            return systemFail();
        }
    }

    /**
     * 预览分享元数据并按接收者权限计算工具可用性。
     */
    @GetMapping("/{token}/preview")
    public Response<SessionShareResponseDTO> preview(@PathVariable String token) {
        try {
            return success(toResponse(shareService.preview(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), token), null));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("预览会话分享失败", e);
            return systemFail();
        }
    }

    /**
     * 下载服务端校验后的 JSON 快照。
     * <p>原始 token 由领域服务校验有效期、撤销状态和下载次数，控制器不直接读取对象存储。</p>
     */
    @GetMapping("/{token}/download")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        SessionShareResultEntity result = shareService.download(token);
        String filename = result.getShare().getShareId() + ".json";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(SessionShareService.CONTENT_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(result.getExportBytes());
    }

    /**
     * 把分享快照复制为当前用户的独立会话。
     * <p>缺失或无权工具必须显式确认风险，不能通过导入继承来源用户权限。</p>
     */
    @PostMapping("/{token}/import")
    public Response<SessionShareResponseDTO> importCopy(@PathVariable String token,
                                                        @RequestBody(required = false) ImportSessionShareRequestDTO request) {
        try {
            SessionShareResultEntity result = shareService.importCopy(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), token,
                    request != null && Boolean.TRUE.equals(request.getConfirmToolAccessRisk()));
            return success(toResponse(result, null));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("导入会话分享失败", e);
            return systemFail();
        }
    }

    /**
     * 撤销本人创建的分享；已导入副本不受影响。
     */
    @PostMapping("/{shareId}/revoke")
    public Response<Void> revoke(@PathVariable String shareId) {
        try {
            shareService.revoke(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(), shareId);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo()).build();
        } catch (AppException e) {
            return Response.<Void>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("撤销会话分享失败 shareId:{}", shareId, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    /** 组合分享状态、可选快照消息、工具依赖和接收者权限预检结果。 */
    private SessionShareResponseDTO toResponse(SessionShareResultEntity result, String token) {
        List<SessionShareResponseDTO.Message> messages = result.getMessages() == null ? null
                : result.getMessages().stream().map(this::toMessage).toList();
        String shareUrl = token == null ? null : "/share/" + token;
        String downloadUrl = token == null ? null : "/api/v1/session-shares/" + token + "/download";
        List<SessionShareResponseDTO.ToolDependency> dependencies = result.getToolDependencies() == null ? List.of()
                : result.getToolDependencies().stream().map(this::toToolDependency).toList();
        SessionShareResponseDTO.ToolPrecheck precheck = result.getToolPrecheck() == null ? null
                : SessionShareResponseDTO.ToolPrecheck.builder().hasRisk(result.getToolPrecheck().getHasRisk())
                .availableCount(result.getToolPrecheck().getAvailableCount())
                .missingCount(result.getToolPrecheck().getMissingCount())
                .deniedCount(result.getToolPrecheck().getDeniedCount())
                .items(result.getToolPrecheck().getItems().stream().map(this::toToolAccess).toList()).build();
        return SessionShareResponseDTO.builder().shareId(result.getShare().getShareId()).shareUrl(shareUrl)
                .downloadUrl(downloadUrl).status(result.getShare().getStatus())
                .expiresAt(result.getShare().getExpiresAt()).maxDownloads(result.getShare().getMaxDownloads())
                .downloadCount(result.getShare().getDownloadCount()).messageCount(result.getShare().getMessageCount())
                .title(result.getShare().getTitle()).sessionId(result.getSession() == null ? null : result.getSession().getSessionId())
                .agentId(result.getSession() == null ? result.getSourceAgentId() : result.getSession().getAgentId())
                .agentName(result.getSession() == null ? result.getSourceAgentName() : result.getSession().getAgentName())
                .appName(result.getSession() == null ? result.getSourceAppName() : result.getSession().getAppName())
                .formatVersion(Boolean.TRUE.equals(result.getLegacySnapshot()) ? 1 : 2)
                .sourceType(result.getSourceType()).workflowId(result.getWorkflowId())
                .workflowVersion(result.getWorkflowVersion()).modelCode(result.getModelCode())
                .legacySnapshot(result.getLegacySnapshot()).toolDependencies(dependencies).toolPrecheck(precheck)
                .messages(messages).build();
    }

    /** 转换快照记录的工具版本依赖。 */
    private SessionShareResponseDTO.ToolDependency toToolDependency(SessionToolDependencyEntity item) {
        return SessionShareResponseDTO.ToolDependency.builder().toolType(item.getToolType()).toolId(item.getToolId())
                .toolName(item.getToolName()).version(item.getVersion()).source(item.getSource()).build();
    }

    /** 转换接收者对单个工具的可用、缺失或拒绝状态。 */
    private SessionShareResponseDTO.ToolAccess toToolAccess(SessionToolAccessEntity item) {
        return SessionShareResponseDTO.ToolAccess.builder().toolType(item.getToolType()).toolId(item.getToolId())
                .toolName(item.getToolName()).version(item.getVersion()).source(item.getSource())
                .access(item.getAccess()).reason(item.getReason()).build();
    }

    /** 转换分享快照中的有效消息。 */
    private SessionShareResponseDTO.Message toMessage(ChatMessageEntity message) {
        return SessionShareResponseDTO.Message.builder().id(message.getMessageId()).role(message.getRole())
                .contentType(message.getContentType()).content(message.getContent()).sequenceNo(message.getSequenceNo())
                .createdAt(message.getCreateTime()).build();
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 将分享领域异常映射为稳定错误码。 */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /** 隐藏快照存储和解析异常的技术细节。 */
    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }
}
