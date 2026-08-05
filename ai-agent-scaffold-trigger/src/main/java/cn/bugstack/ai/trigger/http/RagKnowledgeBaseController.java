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
 * 知识库「建、看、改名」三个 HTTP 入口。
 *
 * <p>解决什么问题：RAG 要用的资料得先有个容器装起来，这个容器就是知识库。用户需要能建新知识库、
 * 看自己能访问哪些、以及改它的名字和说明。真正往里面塞文档、切块、算向量是另外的接口，不在这里。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端的知识库管理页面，通过 /api/v1/rag/knowledge-bases 调用。</p>
 *
 * <p>它向下调用什么：只调 {@code RagKnowledgeBaseManagementService}——由它做角色权限判断、
 * 名称唯一性校验、乐观锁更新，以及「当前用户能看见哪些知识库」的可见性过滤。</p>
 *
 * <p>它不负责什么：不上传文档、不做切块和向量化、不管知识库删除（那是单独的异步删除接口）、
 * 不判断谁有权改、不生成 generation。这里只做三件事：注入可信身份、强制要求编辑必须带版本号、
 * 把领域实体和枚举翻成稳定的对外 DTO。</p>
 */
@RestController
@RequestMapping("/api/v1/rag/knowledge-bases")
public class RagKnowledgeBaseController {

    /**
     * 知识库管理领域服务，本控制器唯一的下游依赖。
     *
     * <p>建、查、改都走它。租户隔离、角色校验、名称冲突判断、乐观锁比对全部在它内部完成，
     * 所以这里传进去的 tenantId 决定了用户绝不可能读写到别的租户的知识库。
     * final 且构造注入，并发请求共享同一实例。</p>
     */
    private final RagKnowledgeBaseManagementService service;

    /**
     * 启动时由 Spring 注入知识库管理服务，注入后依赖不再变化。
     *
     * @param service 知识库管理领域服务
     */
    public RagKnowledgeBaseController(RagKnowledgeBaseManagementService service) {
        // 保存领域服务引用；这是本类唯一的初始化动作。
        this.service = service;
    }

    /**
     * 为当前租户创建一个独立知识库。
     *
     * <p>新建出来的知识库带着初始 generation 和 revision：generation 是「第几代内容」，
     * 每次全量重建索引会加一，用来把旧向量和新向量隔开；revision 是乐观锁版本，
     * 前端后续改名或删除都必须原样带回来。</p>
     *
     * <p>会写数据库。主要失败情形：角色无权创建、名称为空或与已有知识库重名。</p>
     *
     * @param request 名称和说明；空请求交由领域层返回明确校验错误
     * @return 新知识库及初始 generation/revision
     */
    @PostMapping
    public Response<RagKnowledgeBaseResponseDTO> create(
            @RequestBody(required = false) RagKnowledgeBaseCreateRequestDTO request) {
        // 权限不足、名称重复等都是领域层的可预期拒绝，统一接住翻译成业务错误码。
        try {
            // 创建人和角色来自 JWT 上下文，不能使用请求体伪造管理员身份。
            RagKnowledgeBaseEntity created = service.create(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), request == null ? null : request.getName(),
                    request == null ? null : request.getDescription());
            // 创建成功，把新知识库（含 generation 和 revision）返回；前端需要保存 revision 供后续编辑使用。
            return success(toResponse(created));
        } catch (AppException e) {
            // 原样返回领域层给出的错误码和文案，前端据此提示用户改名或申请权限。
            return failure(e);
        }
    }

    /**
     * 查询当前用户在本租户内能看见的知识库列表。
     *
     * <p>可见性不是简单的「本租户全部」：领域层还会按用户和知识库的可见范围过滤，
     * 所以同一租户下不同用户看到的列表可能不一样。不写库、不改状态。</p>
     */
    @GetMapping
    public Response<List<RagKnowledgeBaseResponseDTO>> list() {
        // 领域层可能因为身份缺失拒绝，统一接住转成错误码。
        try {
            // 用租户加用户两个维度取可见列表，再逐个裁剪成对外 DTO，避免把领域实体直接序列化出去。
            return success(service.list(TenantContextHolder.getTenantId(), TenantContextHolder.getUserId())
                    .stream().map(this::toResponse).toList());
        } catch (AppException e) {
            // 身份不合法时返回业务错误码，不返回空列表——否则前端会以为「你确实没有知识库」。
            return failure(e);
        }
    }

    /**
     * 修改知识库的名称和说明。
     *
     * <p>为什么必须带 revision：知识库管理页面可能同时被多个管理员打开。如果不带版本号，
     * 后保存的人会静默覆盖前一个人的修改，谁都不知道发生了什么。带上版本号后，
     * 基于过期页面的保存会被明确拒绝，用户会被要求刷新后重试。</p>
     *
     * <p>会写数据库并递增 revision。主要失败情形：没带 revision、版本与库里不一致、角色无权编辑、新名称重复。</p>
     *
     * @param knowledgeBaseId 知识库ID
     * @param request 新元数据和客户端期望版本
     * @return 乐观锁更新后的知识库
     */
    @PutMapping("/{knowledgeBaseId}")
    public Response<RagKnowledgeBaseResponseDTO> update(@PathVariable String knowledgeBaseId,
                                                        @RequestBody(required = false)
                                                        // 请求体允许缺省，缺省时下面会因为拿不到 revision 而直接拒绝，不会退化成无条件覆盖。
                                                        RagKnowledgeBaseUpdateRequestDTO request) {
        // 版本缺失由本方法直接拒绝，其余校验由领域层负责，两类异常都收敛成统一响应。
        try {
            // 强制携带 revision，防止两个管理员的编辑静默覆盖。
            if (request == null || request.getExpectedRevision() == null) {
                // 没带版本号就直接拒绝，绝不做「无条件覆盖」的写入。
                throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_REQUIRED", "编辑知识库必须提供revision");
            }
            // 带着可信身份、角色、期望版本和新元数据交给领域层做乐观锁更新，成功后返回更新结果（含新的 revision）。
            return success(toResponse(service.update(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), knowledgeBaseId,
                    request.getExpectedRevision(), request.getName(), request.getDescription())));
        } catch (AppException e) {
            // 版本冲突或权限不足时原样返回业务错误码，前端应提示刷新页面后重试。
            return failure(e);
        }
    }

    /**
     * 把知识库领域实体翻成对外 DTO。
     *
     * <p>这是一道边界：可见性和状态在领域层是枚举，直接序列化出去会变成大写的 Java 常量名，
     * 一旦以后枚举改名前端就跟着坏。这里统一转成小写字符串，作为稳定的 API 取值。</p>
     *
     * <p>不查库、不改状态，纯结构转换。</p>
     */
    private RagKnowledgeBaseResponseDTO toResponse(RagKnowledgeBaseEntity value) {
        // 逐字段搬运：身份与说明、可见性与状态（枚举转小写）、向量维度、内容代数，以及乐观锁版本。
        return RagKnowledgeBaseResponseDTO.builder()
                .knowledgeBaseId(value.knowledgeBaseId()).name(value.name()).description(value.description())
                .visibility(value.visibility().name().toLowerCase()).status(value.status().name().toLowerCase())
                .embeddingDimension(value.embeddingDimension()).currentGeneration(value.currentGeneration())
                .revision(value.revision()).build();
    }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 业务数据，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 把领域层抛出的业务异常原样翻译成响应：错误码和文案都是设计好的，可直接展示给用户，不带 data。 */
    private <T> Response<T> failure(AppException e) {
        // 只回错误码和文案，前端据此提示具体原因。
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }
}
