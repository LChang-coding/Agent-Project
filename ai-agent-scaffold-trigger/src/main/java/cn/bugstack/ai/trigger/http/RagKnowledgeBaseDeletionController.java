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
 * 知识库级联删除的异步任务入口：受理删除、查进度、失败重试。
 *
 * <p>解决什么问题：删一个知识库要连带清掉它下面所有文档、分块、向量和原始文件，量大时可能几分钟都跑不完。
 * 如果做成同步接口，HTTP 请求必然超时，用户还会以为删失败了。所以这里改成「受理即返回一个任务」，
 * 真正的清理由后台分阶段推进，前端拿着 taskId 轮询进度。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端的知识库管理页面（删除按钮 + 进度条 + 重试按钮），通过 /api/v1/rag 调用。</p>
 *
 * <p>它向下调用什么：只调 {@code RagKnowledgeBaseDeletionService}——由它创建删除任务、按阶段推进清理、
 * 记录 checkpoint（当前阶段、已处理文档数）、控制重试次数与下次重试时间。</p>
 *
 * <p>它不负责什么：不真正删任何数据、不调向量库、不管后台调度节奏、不判断谁有权删。
 * 这里只做三件事：注入可信身份、强制要求删除必须带版本号、把任务实体和 checkpoint 摊平成前端好显示的进度。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagKnowledgeBaseDeletionController {
    /**
     * 知识库删除领域服务，本控制器唯一的下游依赖。
     *
     * <p>受理、查询、重试都走它。租户与用户隔离、角色校验、乐观锁比对、重试次数上限判断全部在它内部完成。
     * final 且构造注入，并发请求共享同一实例。</p>
     */
    private final RagKnowledgeBaseDeletionService service;

    /**
     * 启动时由 Spring 注入删除领域服务，注入后依赖不再变化。
     *
     * @param service 知识库级联删除领域服务
     */
    public RagKnowledgeBaseDeletionController(RagKnowledgeBaseDeletionService service) {
        // 保存领域服务引用；这是本类唯一的初始化动作。
        this.service = service;
    }

    /**
     * 受理一次知识库删除请求，创建后台删除任务。
     *
     * <p>为什么强制要 revision：删除不可逆。管理页面可能是几分钟前打开的，期间别人可能改过甚至换了内容，
     * 拿过期页面上的按钮去删很容易删错对象。带上版本号后，只要库里版本已变就直接拒绝，逼用户刷新确认。</p>
     *
     * <p>会写数据库（创建删除任务并把知识库标记为待删除），不在本次请求里真正清理数据。
     * 主要失败情形：没带 revision、版本与库里不一致、角色无权删除、该知识库已有进行中的删除任务。</p>
     *
     * @param knowledgeBaseId 待删除知识库
     * @param request 客户端读取到的知识库版本
     * @return 可轮询的异步删除任务
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/delete-tasks")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> requestDeletion(
            @PathVariable String knowledgeBaseId,
            @RequestBody(required = false) RagKnowledgeBaseDeleteRequestDTO request) {
        // 版本缺失由本方法直接拒绝，其余校验由领域层负责，两类异常都收敛成统一响应。
        try {
            // 删除不可逆，必须用 revision 阻止基于过期页面误删已被修改的知识库。
            if (request == null || request.getExpectedRevision() == null) {
                // 没带版本号就不受理，绝不做「无条件删除」，这是防误删的最后一道闸。
                throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_REQUIRED",
                        "删除知识库必须提供revision");
            }
            // 带着可信身份、角色、知识库和期望版本创建删除任务，并把任务快照返回给前端开始轮询。
            return success(toResponse(service.requestDeletion(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), knowledgeBaseId, request.getExpectedRevision())));
        } catch (AppException e) {
            // 版本冲突、权限不足或已有进行中任务，都原样返回业务错误码，不创建重复任务。
            return failure(e);
        }
    }

     /**
      * 按任务ID查询删除进度，供前端轮询进度条。
      *
      * <p>返回的是当前阶段（清文档 / 清分块 / 清向量 / 清原文件）、已完成与总文档数、以及失败信息，
      * 前端据此显示百分比和「重试」按钮。不写库、不改状态。</p>
      *
      * @param taskId 删除任务ID
      * @return 当前阶段、文档进度和失败信息
      */
     */
    @GetMapping("/knowledge-base-delete-tasks/{taskId}")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> task(@PathVariable String taskId) {
        // 任务不存在或不属于当前用户都会抛业务异常，统一接住转成错误码。
        try {
            // 用可信身份和角色读取任务；归属校验在领域层完成，所以拿不到别人的任务进度。
            return success(toResponse(service.requireTask(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            // 任务不存在或无权查看时返回业务错误码，前端应停止轮询。
            return failure(e);
        }
    }

    /**
     * 按知识库ID反查它当前关联的删除任务。
     *
     * <p>用途：用户刷新页面后手里只有知识库ID，没有 taskId，需要靠这个接口把进度条重新接上。
     * 不写库、不改状态。</p>
     *
     * @param knowledgeBaseId 知识库ID
     * @return 该知识库的删除任务快照
     */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/delete-task")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> taskByKnowledgeBase(
            @PathVariable String knowledgeBaseId) {
        // 该知识库没有删除任务时领域层会抛业务异常，统一接住转成错误码。
        try {
            // 用可信身份按知识库反查任务，让刷新过页面的前端能重新接上进度。
            return success(toResponse(service.requireTaskByKnowledgeBase(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), knowledgeBaseId)));
        } catch (AppException e) {
            // 查不到对应任务时返回业务错误码，前端据此认为「当前没有删除在进行」。
            return failure(e);
        }
    }

    /**
     * 重新调度一个失败但仍可重试的删除任务。
     *
     * <p>删除是分阶段推进的，中途失败（例如向量库临时不可用）时任务会停在 checkpoint 上。
     * 重试不是从头再删一遍，而是把任务状态重置后从记录的阶段和文档位置继续，已经删掉的不会重复处理。</p>
     *
     * <p>会写数据库（重置任务状态并重新排队）。主要失败情形：任务不是失败状态、重试次数已达上限、角色无权操作。</p>
     *
     * @param taskId 删除任务ID
     * @return 重置后的任务状态
     */
    @PostMapping("/knowledge-base-delete-tasks/{taskId}/retry")
    public Response<RagKnowledgeBaseDeleteTaskResponseDTO> retry(@PathVariable String taskId) {
        // 状态不允许重试或次数超限都是可预期拒绝，统一接住转成错误码。
        try {
            // 交给领域层重置任务并重新排队，返回新的任务快照供前端继续轮询。
            return success(toResponse(service.retry(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            // 不可重试时返回业务错误码，前端应提示用户联系管理员而不是反复点重试。
            return failure(e);
        }
    }

    /**
     * 把删除任务实体和它的 checkpoint 摊平成前端能直接渲染的进度。
     *
     * <p>checkpoint 在领域层是嵌套结构，里面记着「删到哪个阶段、哪个文档」。这里把它摊到同一层，
     * 并把状态枚举转成小写字符串作为稳定的 API 取值，前端不必了解领域模型的层次。</p>
     *
     * <p>不查库、不改状态，纯结构转换；入参为空会抛空指针，调用方必须先确认有值。</p>
     */
    private RagKnowledgeBaseDeleteTaskResponseDTO toResponse(RagKnowledgeBaseDeleteTaskEntity task) {
        // 逐字段搬运：任务与知识库身份、发起人、状态与阶段、文档进度、重试次数与下次重试时间、
         // 失败码与失败原因，最后带上乐观锁版本。
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

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 任务快照，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 把领域层抛出的业务异常原样翻译成响应：错误码和文案都是设计好的，可直接展示给用户，不带 data。 */
    private <T> Response<T> failure(AppException e) {
        // 只回错误码和文案，前端据此提示具体原因。
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }
}
