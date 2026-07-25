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

/**
 * 知识库级联删除的异步任务入口。
 * <p>删除由后台任务分阶段清理文档、分块、向量和原文件；HTTP 请求只负责受理、查询和失败重试。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagKnowledgeBaseDeletionController {
    private final RagKnowledgeBaseDeletionService service;

    /**
     * @param service 知识库级联删除领域服务
     */
    public RagKnowledgeBaseDeletionController(RagKnowledgeBaseDeletionService service) {
        this.service = service;
    }

    /**
     * 创建知识库删除任务。
     *
     * @param knowledgeBaseId 待删除知识库
     * @param request 客户端读取到的知识库版本
     * @return 可轮询的异步删除任务
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/delete-tasks")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> requestDeletion(
            @PathVariable String knowledgeBaseId,
            @RequestBody(required = false) RagKnowledgeBaseDeleteRequestDTO request) {
        try {
            // 删除不可逆，必须用 revision 阻止基于过期页面误删已被修改的知识库。
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

    /**
     * 按任务ID查询删除进度。
     *
     * @param taskId 删除任务ID
     * @return 当前阶段、文档进度和失败信息
     */
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

    /**
     * 查询知识库当前关联的删除任务。
     *
     * @param knowledgeBaseId 知识库ID
     * @return 该知识库的删除任务快照
     */
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

    /**
     * 重新调度一个可重试的失败删除任务。
     *
     * @param taskId 删除任务ID
     * @return 重置后的任务状态
     */
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

    /** 将领域 checkpoint 展开为前端可直接展示的删除进度。 */
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

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 将领域业务异常转换为稳定 API 错误。 */
    private <T> Response<T> failure(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }
}
