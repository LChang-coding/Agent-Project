package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.asset.AssetPageResponseDTO;
import cn.bugstack.ai.api.dto.asset.AssetResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.asset.model.AssetUploadCommandEntity;
import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 资产 HTTP 控制器。
 * <p>只从认证上下文读取租户和用户身份。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    /** 创建资产控制器；参数是资产领域服务；返回控制器实例。 */
    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    /** 上传聊天附件；参数是文件和可选会话；返回资产元数据。 */
    @PostMapping(value = "/chat-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<AssetResponseDTO> upload(@RequestPart("file") MultipartFile file,
                                             @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            AssetEntity asset = assetService.uploadChatAttachment(AssetUploadCommandEntity.builder()
                    .tenantId(TenantContextHolder.getTenantId()).ownerUserId(requireUserId()).sessionId(sessionId)
                    .fileName(file.getOriginalFilename()).mimeType(file.getContentType()).bytes(file.getBytes()).build());
            return success(toResponse(asset));
        } catch (AppException e) {
            return failure(e.getCode(), e.getInfo());
        } catch (Exception e) {
            log.error("聊天附件上传失败 userId:{} sessionId:{}", TenantContextHolder.getUserId(), sessionId, e);
            return failure(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo());
        }
    }

    /** 查询资产列表；参数是游标、数量、会话和类型；返回当前用户资产。 */
    @GetMapping
    public Response<AssetPageResponseDTO> list(@RequestParam(value = "cursor", required = false) Long cursor,
                                               @RequestParam(value = "limit", required = false) Integer limit,
                                               @RequestParam(value = "sessionId", required = false) String sessionId,
                                               @RequestParam(value = "kind", required = false) String kind) {
        try {
            int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
            List<AssetEntity> rows = assetService.queryAssets(TenantContextHolder.getTenantId(), requireUserId(),
                    cursor, pageSize + 1, sessionId, kind);
            boolean hasMore = rows.size() > pageSize;
            List<AssetEntity> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
            String nextCursor = hasMore && !pageRows.isEmpty()
                    ? String.valueOf(pageRows.get(pageRows.size() - 1).getId()) : null;
            return success(AssetPageResponseDTO.builder().items(pageRows.stream().map(this::toResponse).toList())
                    .nextCursor(nextCursor).hasMore(hasMore).build());
        } catch (AppException e) {
            return failure(e.getCode(), e.getInfo());
        }
    }

    /** 下载资产；参数是资产ID；返回受控文件内容。 */
    @GetMapping("/{assetId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String assetId) {
        AssetEntity asset = assetService.requireOwned(TenantContextHolder.getTenantId(), requireUserId(), assetId);
        byte[] bytes = assetService.download(TenantContextHolder.getTenantId(), requireUserId(), assetId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(asset.getMimeType());
        } catch (Exception ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(asset.getFileName() == null ? assetId : asset.getFileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(mediaType).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).body(bytes);
    }

    /** 软删除资产；参数是资产ID；返回操作结果。 */
    @DeleteMapping("/{assetId}")
    public Response<Void> delete(@PathVariable String assetId) {
        try {
            assetService.delete(TenantContextHolder.getTenantId(), requireUserId(), assetId);
            return success(null);
        } catch (AppException e) {
            return failure(e.getCode(), e.getInfo());
        }
    }

    private String requireUserId() {
        String userId = TenantContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new AppException("AUTH_CONTEXT_MISSING", "缺少可信用户身份");
        }
        return userId;
    }

    private AssetResponseDTO toResponse(AssetEntity asset) {
        return AssetResponseDTO.builder().assetId(asset.getAssetId()).assetKind(asset.getAssetKind())
                .assetType(asset.getAssetType()).sessionId(asset.getSessionId()).messageId(asset.getMessageId())
                .fileName(asset.getFileName()).mimeType(asset.getMimeType()).sizeBytes(asset.getSizeBytes())
                .sha256(asset.getSha256()).status(asset.getStatus()).parseStatus(asset.getParseStatus())
                .parseError(asset.getParseError()).createTime(asset.getCreateTime()).build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }

    private <T> Response<T> failure(String code, String info) {
        return Response.<T>builder().code(code).info(info).build();
    }
}
