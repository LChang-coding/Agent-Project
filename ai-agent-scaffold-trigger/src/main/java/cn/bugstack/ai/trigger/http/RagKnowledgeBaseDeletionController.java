package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseDeleteRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseDeleteTaskResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseDeletionService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库级联删除受理、进度和失败恢复接口。 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagKnowledgeBaseDeletionController {
    private final RagKnowledgeBaseDeletionService service;

    public RagKnowledgeBaseDeletionController(RagKnowledgeBaseDeletionService service) {
        this.service = service;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/delete-tasks")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> requestDeletion(
            @PathVariable String knowledgeBaseId,
            @RequestBody(required = false) RagKnowledgeBaseDeleteRequestDTO request) {
        try {
            if (request == null || request.getExpectedRevision() == null) {
                throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_REQUIRED",
                        "删除知识库必须提供revision");
            }
            return success(toResponse(service.requestDeletion(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), knowledgeBaseId, request.getExpectedRevision())));
        } catch (AppException e) {
            return failure(e);
        }
    }

    @GetMapping("/knowledge-base-delete-tasks/{taskId}")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> task(@PathVariable String taskId) {
        try {
            return success(toResponse(service.requireTask(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/delete-task")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> taskByKnowledgeBase(
            @PathVariable String knowledgeBaseId) {
        try {
            return success(toResponse(service.requireTaskByKnowledgeBase(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), knowledgeBaseId)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    @PostMapping("/knowledge-base-delete-tasks/{taskId}/retry")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> retry(@PathVariable String taskId) {
        try {
            return success(toResponse(service.retry(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            return failure(e);
        }
    }

    private RagKnowledgeBaseDeleteTaskResponseDTO toResponse(RagKnowledgeBaseDeleteTaskEntity task) {
        return RagKnowledgeBaseDeleteTaskResponseDTO.builder()
                .taskId(task.taskId()).knowledgeBaseId(task.knowledgeBaseId())
                .requestedByUserId(task.requestedByUserId())
                .status(task.status().name().toLowerCase())
                .stage(task.checkpoint().stage().name().toLowerCase())
                .totalDocuments(task.checkpoint().totalDocuments())
                .completedDocuments(task.checkpoint().completedDocuments())
                .currentDocumentId(task.checkpoint().currentDocumentId())
                .attemptCount(task.attemptCount()).maxAttempts(task.maxAttempts())
                .nextRetryAt(task.nextRetryAt()).errorCode(task.errorCode())
                .errorMessage(task.errorMessage()).revision(task.revision()).build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }
}
