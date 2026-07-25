package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.rag.RagDocumentResponseDTO;
import cn.bugstack.ai.api.dto.rag.RagDocumentUploadResponseDTO;
import cn.bugstack.ai.api.dto.rag.RagIngestTaskCancelRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagIngestTaskResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadResult;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagUploadFileCandidate;
import cn.bugstack.ai.domain.rag.service.RagDocumentManagementService;
import cn.bugstack.ai.domain.rag.service.RagDocumentDeletionService;
import cn.bugstack.ai.domain.rag.service.RagDocumentUploadService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RAG 文档上传、查询、删除和摄取任务控制入口。
 * <p>文件先进入受控临时区，领域服务完成格式校验、对象存储登记和异步摄取任务注册。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
public class RagDocumentController {

    private final RagDocumentUploadService uploadService;
    private final RagDocumentManagementService managementService;
    private final RagDocumentDeletionService deletionService;

    /**
     * @param uploadService 文档上传受理服务
     * @param managementService 文档和摄取任务查询控制服务
     * @param deletionService 文档异步删除服务
     */
    public RagDocumentController(RagDocumentUploadService uploadService,
                                 RagDocumentManagementService managementService,
                                 RagDocumentDeletionService deletionService) {
        this.uploadService = uploadService;
        this.managementService = managementService;
        this.deletionService = deletionService;
    }

    /**
     * 上传一个待摄取文档。
     *
     * @param knowledgeBaseId 目标知识库
     * @param file Word、PDF 或 Markdown 文件
     * @return 文档、版本和异步摄取任务身份
     */
    @PostMapping(value = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<RagDocumentUploadResponseDTO> upload(@PathVariable String knowledgeBaseId,
                                                          @RequestPart("file") MultipartFile file) {
        // 临时路径只在本次 HTTP 请求内有效，领域服务必须在返回前完成持久化接管。
        Path staged = null;
        try {
            if (file == null) throw new AppException("RAG_FILE_INVALID", "上传文件不能为空");
            // MultipartFile 的底层临时资源生命周期由容器控制，先转存到项目可读路径再交给领域层。
            staged = Files.createTempFile("rag-upload-", ".staged");
            file.transferTo(staged);
            // 租户、用户和角色来自 JWT 上下文，浏览器只能指定目标知识库和文件。
            RagDocumentUploadResult result = uploadService.upload(new RagDocumentUploadCommand(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), knowledgeBaseId,
                    new RagUploadFileCandidate(staged, file.getSize(), file.getOriginalFilename(),
                            file.getContentType())));
            return success(RagDocumentUploadResponseDTO.builder().documentId(result.documentId())
                    .versionId(result.versionId()).taskId(result.taskId()).fileName(result.fileName())
                    .sizeBytes(result.sizeBytes()).status(result.status())
                    .deduplicated(result.deduplicated()).build());
        } catch (AppException e) {
            return failure(e);
        } catch (Exception e) {
            // 未知 I/O 或存储异常只返回统一错误，详细原因保留在服务端日志。
            log.error("RAG文档上传失败 kbId:{}", knowledgeBaseId, e);
            return failure(new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()));
        } finally {
            // 无论上传成功与否都清理请求级暂存文件；对象存储中的正式副本不受影响。
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (Exception cleanupError) {
                    log.warn("RAG上传临时文件清理失败 kbId:{}", knowledgeBaseId);
                }
            }
        }
    }

    /** 查询当前用户可访问的知识库文档及激活版本摘要。 */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public Response<List<RagDocumentResponseDTO>> list(@PathVariable String knowledgeBaseId) {
        try {
            return success(managementService.listDocuments(TenantContextHolder.getTenantId(),
                            TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), knowledgeBaseId)
                    .stream().map(this::documentResponse).toList());
        } catch (AppException e) {
            return failure(e);
        }
    }

    /**
     * 受理覆盖全部版本的异步文档删除。
     *
     * @param knowledgeBaseId 所属知识库
     * @param documentId 待删除文档
     * @param expectedRevision 客户端读取到的文档版本
     * @return 负责清理向量、分块和原文件的后台任务
     */
    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public Response<RagIngestTaskResponseDTO> delete(@PathVariable String knowledgeBaseId,
                                                      @PathVariable String documentId,
                                                      @RequestParam long expectedRevision) {
        try {
            return success(taskResponse(deletionService.deleteDocument(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), knowledgeBaseId,
                    documentId, expectedRevision)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 按任务ID查询解析、切块、向量化和索引阶段进度。 */
    @GetMapping("/ingest-tasks/{taskId}")
    public Response<RagIngestTaskResponseDTO> task(@PathVariable String taskId) {
        try {
            return success(taskResponse(managementService.requireTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 查询知识库近期摄取任务，供前端恢复刷新后的进度视图。 */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/ingest-tasks")
    public Response<List<RagIngestTaskResponseDTO>> tasks(@PathVariable String knowledgeBaseId,
                                                           @RequestParam(defaultValue = "100") int limit) {
        try {
            return success(managementService.listTasks(TenantContextHolder.getTenantId(),
                            TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(),
                            knowledgeBaseId, limit)
                    .stream().map(this::taskResponse).toList());
        } catch (AppException e) {
            return failure(e);
        }
    }

    /**
     * 请求取消摄取任务。
     * <p>返回的是最新任务快照；Worker 会在安全检查点停止并执行必要清理。</p>
     */
    @PostMapping("/ingest-tasks/{taskId}/cancel")
    public Response<RagIngestTaskResponseDTO> cancel(@PathVariable String taskId,
                                                      @RequestBody(required = false)
                                                      RagIngestTaskCancelRequestDTO request) {
        try {
            return success(taskResponse(managementService.cancelTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId,
                    request == null ? null : request.getReason())));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 将允许重试的失败或死信任务重新放回摄取链路。 */
    @PostMapping("/ingest-tasks/{taskId}/retry")
    public Response<RagIngestTaskResponseDTO> retry(@PathVariable String taskId) {
        try {
            return success(taskResponse(managementService.retryTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 转换文档及其激活版本、目标 generation 和内容规模摘要。 */
    private RagDocumentResponseDTO documentResponse(RagDocumentEntity value) {
        return RagDocumentResponseDTO.builder().documentId(value.documentId())
                .knowledgeBaseId(value.knowledgeBaseId()).displayName(value.displayName())
                .status(value.status().name().toLowerCase(java.util.Locale.ROOT))
                .activeVersionId(value.activeVersionId()).activeGeneration(value.activeGeneration())
                .targetGeneration(value.targetGeneration()).revision(value.revision())
                .pageCount(value.pageCount()).chunkCount(value.chunkCount()).build();
    }

    /** 展开摄取 checkpoint，让前端无需理解领域状态对象。 */
    private RagIngestTaskResponseDTO taskResponse(RagIngestJobEntity value) {
        return RagIngestTaskResponseDTO.builder().taskId(value.jobId())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .versionId(value.versionId()).operation(value.operation().name().toLowerCase(java.util.Locale.ROOT))
                .stage(value.checkpoint().stage().name().toLowerCase(java.util.Locale.ROOT))
                .status(value.status().name().toLowerCase(java.util.Locale.ROOT))
                .processedChunks(value.checkpoint().processedChunks()).totalChunks(value.checkpoint().totalChunks())
                .attemptCount(value.attemptCount()).maxAttempts(value.maxAttempts())
                .errorCode(value.errorCode()).cancelReason(value.cancelReason()).revision(value.revision()).build();
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 保留领域错误码和可公开错误信息。 */
    private <T> Response<T> failure(AppException error) {
        return Response.<T>builder().code(error.getCode()).info(error.getInfo()).build();
    }
}
