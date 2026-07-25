package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseCreateRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseResponseDTO;
import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseUpdateRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseManagementService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户知识库元数据管理入口。
 * <p>只负责身份注入和 DTO 转换；文档摄取、向量写入和生命周期状态不在本控制器处理。</p>
 */
@RestController
@RequestMapping("/api/v1/rag/knowledge-bases")
public class RagKnowledgeBaseController {

    private final RagKnowledgeBaseManagementService service;

    /**
     * @param service 知识库管理领域服务
     */
    public RagKnowledgeBaseController(RagKnowledgeBaseManagementService service) {
        this.service = service;
    }

    /**
     * 为当前租户创建独立知识库。
     *
     * @param request 名称和说明；空请求交由领域层返回明确校验错误
     * @return 新知识库及初始 generation/revision
     */
    @PostMapping
    public Response<RagKnowledgeBaseResponseDTO> create(
            @RequestBody(required = false) RagKnowledgeBaseCreateRequestDTO request) {
        try {
            // 创建人和角色来自 JWT 上下文，不能使用请求体伪造管理员身份。
            RagKnowledgeBaseEntity created = service.create(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), request == null ? null : request.getName(),
                    request == null ? null : request.getDescription());
            return success(toResponse(created));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /**
     * 查询当前用户在本租户内可见的知识库。
     */
    @GetMapping
    public Response<List<RagKnowledgeBaseResponseDTO>> list() {
        try {
            return success(service.list(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId())
                    .stream().map(this::toResponse).toList());
        } catch (AppException e) {
            return failure(e);
        }
    }

    /**
     * 编辑知识库名称和说明。
     *
     * @param knowledgeBaseId 知识库ID
     * @param request 新元数据和客户端期望版本
     * @return 乐观锁更新后的知识库
     */
    @PutMapping("/{knowledgeBaseId}")
    public Response<RagKnowledgeBaseResponseDTO> update(@PathVariable String knowledgeBaseId,
                                                        @RequestBody(required = false)
                                                        RagKnowledgeBaseUpdateRequestDTO request) {
        try {
            // 强制携带 revision，防止两个管理员的编辑静默覆盖。
            if (request == null || request.getExpectedRevision() == null) {
                throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_REQUIRED", "编辑知识库必须提供revision");
            }
            return success(toResponse(service.update(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), knowledgeBaseId,
                    request.getExpectedRevision(), request.getName(), request.getDescription())));
        } catch (AppException e) {
            return failure(e);
        }
    }

    /** 将领域枚举转换为稳定的小写 API 值。 */
    private RagKnowledgeBaseResponseDTO toResponse(RagKnowledgeBaseEntity value) {
        return RagKnowledgeBaseResponseDTO.builder()
                .knowledgeBaseId(value.knowledgeBaseId()).name(value.name()).description(value.description())
                .visibility(value.visibility().name().toLowerCase()).status(value.status().name().toLowerCase())
                .embeddingDimension(value.embeddingDimension()).currentGeneration(value.currentGeneration())
                .revision(value.revision()).build();
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 保留领域层给出的业务错误码和用户可读原因。 */
    private <T> Response<T> failure(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }
}
