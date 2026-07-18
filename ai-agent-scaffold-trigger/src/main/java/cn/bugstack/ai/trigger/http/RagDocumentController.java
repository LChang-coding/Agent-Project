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
import cn.bugstack.ai.domain.rag.service.RagDocumentUploadService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** RAG 文档上传、列表和摄取任务管理接口。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
public class RagDocumentController {

    private final RagDocumentUploadService uploadService;
    private final RagDocumentManagementService managementService;

    public RagDocumentController(RagDocumentUploadService uploadService,
                                 RagDocumentManagementService managementService) {
        this.uploadService = uploadService;
        this.managementService = managementService;
    }

    /** 将 Multipart 流转存到受控临时文件后提交上传用例。 */
    @PostMapping(value = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<RagDocumentUploadResponseDTO> upload(@PathVariable String knowledgeBaseId,
                                                          @RequestPart("file") MultipartFile file) {
        Path staged = null;
        try {
            if (file == null) throw new AppException("RAG_FILE_INVALID", "上传文件不能为空");
            staged = Files.createTempFile("rag-upload-", ".staged");
            file.transferTo(staged);
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
            log.error("RAG文档上传失败 kbId:{}", knowledgeBaseId, e);
            return failure(new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()));
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (Exception cleanupError) {
                    log.warn("RAG上传临时文件清理失败 kbId:{}", knowledgeBaseId);
                }
            }
        }
    }

    /** 查询知识库文档。 */
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

    /** 查询摄取任务公开状态。 */
    @GetMapping("/ingest-tasks/{taskId}")
    public Response<RagIngestTaskResponseDTO> task(@PathVariable String taskId) {
        try {
            return success(taskResponse(managementService.requireTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 取消摄取任务。 */
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

    private RagDocumentResponseDTO documentResponse(RagDocumentEntity value) {
        return RagDocumentResponseDTO.builder().documentId(value.documentId())
                .knowledgeBaseId(value.knowledgeBaseId()).displayName(value.displayName())
                .status(value.status().name().toLowerCase(java.util.Locale.ROOT))
                .activeVersionId(value.activeVersionId()).activeGeneration(value.activeGeneration())
                .targetGeneration(value.targetGeneration()).revision(value.revision()).build();
    }

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

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(AppException error) {
        return Response.<T>builder().code(error.getCode()).info(error.getInfo()).build();
    }
}
