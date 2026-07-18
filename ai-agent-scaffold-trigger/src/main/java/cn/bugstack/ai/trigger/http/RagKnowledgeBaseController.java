package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseCreateRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseManagementService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 租户知识库管理接口。 */
@RestController
@RequestMapping("/api/v1/rag/knowledge-bases")
public class RagKnowledgeBaseController {

    private final RagKnowledgeBaseManagementService service;

    public RagKnowledgeBaseController(RagKnowledgeBaseManagementService service) {
        this.service = service;
    }

    /** 创建当前租户的知识库。 */
    @PostMapping
    public Response<RagKnowledgeBaseResponseDTO> create(
            @RequestBody(required = false) RagKnowledgeBaseCreateRequestDTO request) {
        try {
            RagKnowledgeBaseEntity created = service.create(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), request == null ? null : request.getName(),
                    request == null ? null : request.getDescription());
            return success(toResponse(created));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 查询当前租户的知识库。 */
    @GetMapping
    public Response<List<RagKnowledgeBaseResponseDTO>> list() {
        try {
            return success(service.list(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId())
                    .stream().map(this::toResponse).toList());
        } catch (AppException e) {
            return failure(e);
        }
    }

    private RagKnowledgeBaseResponseDTO toResponse(RagKnowledgeBaseEntity value) {
        return RagKnowledgeBaseResponseDTO.builder()
                .knowledgeBaseId(value.knowledgeBaseId()).name(value.name()).description(value.description())
                .visibility(value.visibility().name().toLowerCase()).status(value.status().name().toLowerCase())
                .embeddingDimension(value.embeddingDimension()).currentGeneration(value.currentGeneration())
                .revision(value.revision()).build();
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> failure(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }
}
