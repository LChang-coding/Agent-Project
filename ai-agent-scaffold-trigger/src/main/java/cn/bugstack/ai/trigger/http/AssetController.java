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
 * 会话附件与资产中心 HTTP 入口。
 * <p>只从认证上下文读取租户和用户身份；资产归属、对象存储和解析状态由领域服务管理。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    /** @param assetService 资产上传、查询、下载和删除领域服务 */
    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    /**
     * 上传聊天附件并登记资产。
     *
     * @param file 附件内容和客户端文件元数据
     * @param sessionId 可选目标会话；提供时由领域层校验归属
     * @return 可在后续消息中引用的资产元数据
     */
    @PostMapping(value = "/chat-attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<AssetResponseDTO> upload(@RequestPart("file") MultipartFile file,
                                             @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            // 所有者强制使用当前 JWT 用户，防止请求体伪造资产归属。
            AssetEntity asset = assetService.uploadChatAttachment(AssetUploadCommandEntity.builder()
                    .tenantId(TenantContextHolder.getTenantId()).ownerUserId(requireUserId()).sessionId(sessionId)
                    .fileName(file.getOriginalFilename()).mimeType(file.getContentType()).bytes(file.getBytes()).build());
            return success(toResponse(asset));
        } catch (AppException e) {
            return failure(e.getCode(), e.getInfo());
        } catch (Exception e) {
            // 不在响应中暴露文件系统或对象存储异常，日志保留用户和会话定位字段。
            log.error("聊天附件上传失败 userId:{} sessionId:{}", TenantContextHolder.getUserId(), sessionId, e);
            return failure(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo());
        }
    }

    /**
     * 游标分页查询当前用户资产。
     *
     * @param cursor 上一页末尾数据库游标
     * @param limit 页大小，服务端限制为 1 到 100
     * @param sessionId 可选会话过滤
     * @param kind 可选资产类型过滤
     * @return 当前页、下一游标和是否有更多数据
     */
    @GetMapping
    public Response<AssetPageResponseDTO> list(@RequestParam(value = "cursor", required = false) Long cursor,
                                               @RequestParam(value = "limit", required = false) Integer limit,
                                               @RequestParam(value = "sessionId", required = false) String sessionId,
                                               @RequestParam(value = "kind", required = false) String kind) {
        try {
            // 多取一条判断 hasMore，避免执行额外 count 查询。
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

    /**
     * 下载当前用户拥有的资产。
     *
     * @param assetId 资产ID
     * @return 带安全文件名、媒体类型和长度的文件响应
     */
    @GetMapping("/{assetId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String assetId) {
        // 先校验资产归属，再读取对象内容，避免利用资产ID探测或下载他人文件。
        AssetEntity asset = assetService.requireOwned(TenantContextHolder.getTenantId(), requireUserId(), assetId);
        byte[] bytes = assetService.download(TenantContextHolder.getTenantId(), requireUserId(), assetId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(asset.getMimeType());
        } catch (Exception ignored) {
            // 非法或缺失 MIME 类型使用二进制下载，不能让元数据错误阻断文件取回。
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        // 使用 attachment 强制下载，并按 UTF-8 编码原文件名。
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(asset.getFileName() == null ? assetId : asset.getFileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(mediaType).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).body(bytes);
    }

    /** 软删除当前用户拥有的资产；实际对象清理由领域策略决定。 */
    @DeleteMapping("/{assetId}")
    public Response<Void> delete(@PathVariable String assetId) {
        try {
            assetService.delete(TenantContextHolder.getTenantId(), requireUserId(), assetId);
            return success(null);
        } catch (AppException e) {
            return failure(e.getCode(), e.getInfo());
        }
    }

    /** 读取可信用户身份；缺失身份时在访问资产前立即拒绝。 */
    private String requireUserId() {
        String userId = TenantContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new AppException("AUTH_CONTEXT_MISSING", "缺少可信用户身份");
        }
        return userId;
    }

    /** 将资产领域实体转换为不含存储桶和对象键的公开元数据。 */
    private AssetResponseDTO toResponse(AssetEntity asset) {
        return AssetResponseDTO.builder().assetId(asset.getAssetId()).assetKind(asset.getAssetKind())
                .assetType(asset.getAssetType()).sessionId(asset.getSessionId()).messageId(asset.getMessageId())
                .fileName(asset.getFileName()).mimeType(asset.getMimeType()).sizeBytes(asset.getSizeBytes())
                .sha256(asset.getSha256()).status(asset.getStatus()).parseStatus(asset.getParseStatus())
                .parseError(asset.getParseError()).createTime(asset.getCreateTime()).build();
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(data).build();
    }

    /** 构造不泄露技术异常的失败响应。 */
    private <T> Response<T> failure(String code, String info) {
        return Response.<T>builder().code(code).info(info).build();
    }
}
