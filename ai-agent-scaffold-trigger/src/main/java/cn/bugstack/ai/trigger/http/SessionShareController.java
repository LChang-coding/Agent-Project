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
 * 会话分享与复制导入接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session-shares")
public class SessionShareController {

    private final SessionShareService shareService;

    public SessionShareController(SessionShareService shareService) {
        this.shareService = shareService;
    }

    /**
     * 创建分享；参数是来源会话和生命周期；返回一次性分享链接。
     */
    @PostMapping
    public Response<SessionShareResponseDTO> create(@RequestBody CreateSessionShareRequestDTO request) {
        try {
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
     * 预览分享；参数是原分享令牌；返回标题、数量和有效期。
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
     * 下载分享文件；参数是原分享令牌；返回服务端校验后的 JSON 附件。
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
     * 复制导入分享；参数是原分享令牌；返回接收者独立会话。
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
     * 撤销本人分享；参数是分享ID；返回空成功响应。
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
                .legacySnapshot(result.getLegacySnapshot()).toolDependencies(dependencies).toolPrecheck(precheck)
                .messages(messages).build();
    }

    private SessionShareResponseDTO.ToolDependency toToolDependency(SessionToolDependencyEntity item) {
        return SessionShareResponseDTO.ToolDependency.builder().toolType(item.getToolType()).toolId(item.getToolId())
                .toolName(item.getToolName()).version(item.getVersion()).source(item.getSource()).build();
    }

    private SessionShareResponseDTO.ToolAccess toToolAccess(SessionToolAccessEntity item) {
        return SessionShareResponseDTO.ToolAccess.builder().toolType(item.getToolType()).toolId(item.getToolId())
                .toolName(item.getToolName()).version(item.getVersion()).source(item.getSource())
                .access(item.getAccess()).reason(item.getReason()).build();
    }

    private SessionShareResponseDTO.Message toMessage(ChatMessageEntity message) {
        return SessionShareResponseDTO.Message.builder().id(message.getMessageId()).role(message.getRole())
                .contentType(message.getContentType()).content(message.getContent()).sequenceNo(message.getSequenceNo())
                .createdAt(message.getCreateTime()).build();
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
