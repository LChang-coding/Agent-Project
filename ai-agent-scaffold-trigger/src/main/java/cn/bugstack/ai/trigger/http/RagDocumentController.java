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
 * 知识库文档的上传、查询、删除，以及摄取任务的查进度、取消、重试入口。
 *
 * <p>解决什么问题：一份 Word 或 PDF 要变成能被检索的向量，中间要经过解析、切块、向量化、建索引好几步，
 * 少则几秒多则几分钟。所以上传接口只负责「收下文件并登记一个摄取任务」，剩下的进度由前端轮询，
 * 出错还能重试、跑太久还能取消。删除同理，也是丢给后台任务去清向量和原文件。</p>
 *
 * <p>所属层次：触发器层（trigger），系统最外层，直接面向前端。</p>
 *
 * <p>谁会调用它：Web 前端的知识库文档管理页面（上传框、文档列表、进度条、取消与重试按钮），
 * 通过 /api/v1/rag 下的 HTTP 接口调用。</p>
 *
 * <p>它向下调用什么：
 * 1) {@code RagDocumentUploadService}：校验文件格式与大小、按内容哈希去重、写对象存储、登记摄取任务；
 * 2) {@code RagDocumentManagementService}：查文档列表、查任务进度、取消任务、重试任务；
 * 3) {@code RagDocumentDeletionService}：受理文档删除并创建清理任务。</p>
 *
 * <p>它不负责什么：不解析文档、不切块、不算向量、不写索引、不判断谁有权操作、不控制重试节奏。
 * 唯一一件「实活」是把 multipart 文件转存到本地临时文件再交给领域层，并在请求结束时无条件清理它。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
public class RagDocumentController {

    /**
     * 文档上传受理服务。
     *
     * <p>负责格式校验、按内容哈希判重、写对象存储、创建文档版本和摄取任务。
     * 它必须在本次 HTTP 请求返回前完成对文件的持久化接管，因为下面的临时文件会在 finally 里被删掉。</p>
     */
    private final RagDocumentUploadService uploadService;
    /**
     * 文档与摄取任务的查询和控制服务。
     *
     * <p>列文档、查任务进度、取消任务、重试任务都走它。权限与归属校验在它内部完成，
     * 所以控制器传进去可信身份就够了，不可能读到别的租户的文档。</p>
     */
    private final RagDocumentManagementService managementService;
    /**
     * 文档异步删除服务。
     *
     * <p>删一份文档要连带清掉它所有版本的分块、向量和原始文件，因此只受理成后台任务，
     * 由它按阶段推进并记录 checkpoint，请求本身不做任何实际清理。</p>
     */
    private final RagDocumentDeletionService deletionService;

    /**
     * 启动时由 Spring 注入三个领域服务：上传、查询控制、删除各一个，职责互不重叠。
     *
     * @param uploadService 文档上传受理服务
     * @param managementService 文档和摄取任务查询控制服务
     * @param deletionService 文档异步删除服务
     */
    public RagDocumentController(RagDocumentUploadService uploadService,
                                 RagDocumentManagementService managementService,
                                 RagDocumentDeletionService deletionService) {
        // 保存上传服务引用，供 multipart 上传接口使用。
        this.uploadService = uploadService;
        // 保存查询控制服务引用，供文档列表和任务进度、取消、重试接口使用。
        this.managementService = managementService;
        // 保存删除服务引用，仅供删除接口使用。
        this.deletionService = deletionService;
    }

    /**
     * 上传一份待摄取的文档，登记文档版本并创建异步摄取任务。
     *
     * <p>各层职责：
     * 第一层：先声明临时文件变量，保证无论走到哪个分支 finally 都能拿到它去清理。
     * 第二层：拒绝空文件，并把容器管理的 multipart 临时资源转存成本进程可控的临时文件。
     * 第三层：交给领域层做格式校验、内容去重、写对象存储、建版本与摄取任务。
     * 第四层：把结果裁剪成对外 DTO（含 deduplicated 标记，前端据此提示「该文件已存在」）。
     * 第五层：finally 无条件删除临时文件；清理本身再失败也只记警告，不能影响已经成功的上传结果。</p>
     *
     * <p>数据流：
     * multipart 请求
     * → 转存为本地临时文件
     * → 拼装上传命令（可信租户/用户/角色 + 目标知识库 + 文件候选）
     * → 领域层校验格式并按哈希判重
     * → 写对象存储、建文档版本、登记摄取任务
     * → 返回 documentId/versionId/taskId 给前端轮询
     * → finally 删除临时文件</p>
     *
     * <p>会写对象存储、会写数据库、会创建后台任务。主要失败情形：文件为空、格式不受支持、
     * 角色无权向该知识库上传、磁盘或对象存储不可用。最后一类收敛成系统错误码，细节只留在日志里。</p>
     *
     * @param knowledgeBaseId 目标知识库
     * @param file Word、PDF 或 Markdown 文件
     * @return 文档、版本和异步摄取任务身份
     */
    @PostMapping(value = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    /* 上述映射续行说明：只接收 multipart 表单提交，目标知识库由路径变量给出。 */
    public Response<RagDocumentUploadResponseDTO> upload(@PathVariable String knowledgeBaseId,
                                                          @RequestPart("file") MultipartFile file) {
        // 临时路径只在本次 HTTP 请求内有效，领域服务必须在返回前完成持久化接管。
        Path staged = null;
        // 转存、校验、写存储都可能失败，整段包住；finally 里的临时文件清理必须任何情况下都执行。
        try {
            // 没有文件就没有可摄取的内容，立刻拒绝，不做后面任何昂贵动作。
            if (file == null) throw new AppException("RAG_FILE_INVALID", "上传文件不能为空");
            // MultipartFile 的底层临时资源生命周期由容器控制，先转存到项目可读路径再交给领域层。
            staged = Files.createTempFile("rag-upload-", ".staged");
            // 把上传内容写进这个临时文件；此后领域层读的是本进程可控的路径，不再依赖容器的临时资源。
            file.transferTo(staged);
            // 租户、用户和角色来自 JWT 上下文，浏览器只能指定目标知识库和文件。
            RagDocumentUploadResult result = uploadService.upload(new RagDocumentUploadCommand(
                    TenantContextHolder.getTenantId(), TenantContextHolder.getUserId(),
                    TenantContextHolder.getRoleCode(), knowledgeBaseId,
                    new RagUploadFileCandidate(staged, file.getSize(), file.getOriginalFilename(),
                            file.getContentType())));
            // 上传成功，把文档、版本、任务三个编号和文件摘要返回；deduplicated 为真表示内容已存在、
             // 本次只是复用了已有向量，前端应提示用户不必重复上传。
            return success(RagDocumentUploadResponseDTO.builder().documentId(result.documentId())
                    .versionId(result.versionId()).taskId(result.taskId()).fileName(result.fileName())
                    .sizeBytes(result.sizeBytes()).status(result.status())
                    .deduplicated(result.deduplicated()).build());
        } catch (AppException e) {
            // 领域层明确拒绝（格式不支持、权限不足、知识库不存在），错误码可直接展示给用户。
            return failure(e);
        } catch (Exception e) {
            // 未知 I/O 或存储异常只返回统一错误，详细原因保留在服务端日志。
            log.error("RAG文档上传失败 kbId:{}", knowledgeBaseId, e);
            // 对外统一成系统错误码；此时可能已写入部分数据，需要靠后台任务的状态机继续收敛，不在这里补救。
            return failure(new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()));
        } finally {
            // 无论上传成功与否都清理请求级暂存文件；对象存储中的正式副本不受影响。
            if (staged != null) {
                // 删临时文件也可能失败（文件被占用等），不能让清理动作把已经成功的上传变成失败。
                try {
                    // 删掉本次请求的临时文件；对象存储里的正式副本已由领域层接管，不受影响。
                    Files.deleteIfExists(staged);
                } catch (Exception cleanupError) {
                    // 清理失败只记警告：残留的临时文件由操作系统或运维定期清理，不影响业务结果。
                    log.warn("RAG上传临时文件清理失败 kbId:{}", knowledgeBaseId);
                }
            }
        }
    }

    /**
     * 列出某个知识库下当前用户能看到的文档及其激活版本摘要。
     *
     * <p>激活版本是「当前正在被检索使用的那一版」；文档可能有多版，但只有激活版参与检索。
     * 不写库、不改状态。角色无权访问该知识库时返回业务错误码。</p>
     */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public Response<List<RagDocumentResponseDTO>> list(@PathVariable String knowledgeBaseId) {
        // 权限不足或知识库不存在都是可预期拒绝，统一接住转成错误码。
        try {
            // 用可信身份和角色取文档列表，再逐个裁剪成对外 DTO，避免把领域实体直接序列化出去。
            return success(managementService.listDocuments(TenantContextHolder.getTenantId(),
                            TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), knowledgeBaseId)
                    .stream().map(this::documentResponse).toList());
        } catch (AppException e) {
            // 原样返回业务错误码，不返回空列表——否则前端会误以为这个知识库真的没有文档。
            return failure(e);
        }
    }

    /**
     * 受理一次文档删除，创建覆盖该文档全部版本的后台清理任务。
     *
     * <p>为什么要 expectedRevision：文档可能刚被别人替换成新版本，拿过期页面上的按钮去删很容易删错。
     * 带上版本号后，库里版本一变就直接拒绝，逼用户刷新确认。</p>
     *
     * <p>会写数据库（创建删除任务并标记文档待删除），本次请求不真正清理向量和文件。
     * 主要失败情形：版本不一致、角色无权删除、文档不存在或已有进行中的删除任务。</p>
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
        // 版本冲突和权限不足都由领域层抛业务异常，统一接住转成错误码。
        try {
            // 带着可信身份、角色、文档和期望版本受理删除，并把生成的清理任务快照返回给前端轮询。
            return success(taskResponse(deletionService.deleteDocument(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), knowledgeBaseId,
                    documentId, expectedRevision)));
        } catch (AppException e) {
            // 版本已变或无权删除，原样返回业务错误码，不创建任务。
            return failure(e);
        }
    }

    /**
     * 按任务ID查询一次摄取的阶段进度，供前端刷新进度条。
     *
     * <p>返回的阶段就是摄取链路的四步：解析、切块、向量化、建索引；再配上已处理和总分块数，
     * 前端就能画出百分比。不写库、不改状态。任务不存在或不属于当前用户时返回业务错误码。</p>
     */
    @GetMapping("/ingest-tasks/{taskId}")
    public Response<RagIngestTaskResponseDTO> task(@PathVariable String taskId) {
        // 任务不存在或无权查看都会抛业务异常，统一接住转成错误码。
        try {
            // 用可信身份和角色读取任务，并把 checkpoint 摊平成前端能直接渲染的进度。
            return success(taskResponse(managementService.requireTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            // 查不到任务时返回业务错误码，前端应停止轮询。
            return failure(e);
        }
    }

    /**
     * 列出某个知识库近期的摄取任务，供前端在刷新页面后恢复进度视图。
     *
     * <p>用户上传完就关页面很常见，回来后手里没有 taskId，只能按知识库把最近的任务捞回来重新显示。
     * limit 默认 100，防止一次拉回过多历史任务。不写库、不改状态。</p>
     */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/ingest-tasks")
    public Response<List<RagIngestTaskResponseDTO>> tasks(@PathVariable String knowledgeBaseId,
                                                           @RequestParam(defaultValue = "100") int limit) {
        // 权限不足或知识库不存在时统一转成错误码。
        try {
            // 按可信身份和角色取近期任务，再逐个摊平成前端能直接渲染的进度对象。
            return success(managementService.listTasks(TenantContextHolder.getTenantId(),
                            TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(),
                            knowledgeBaseId, limit)
                    .stream().map(this::taskResponse).toList());
        } catch (AppException e) {
            // 原样返回业务错误码，避免前端把「无权访问」误当成「没有任务」。
            return failure(e);
        }
    }

    /**
     * 请求取消一次正在进行的摄取任务。
     *
     * <p>注意这不是「立刻停」：后台 Worker 只会在安全检查点（一个分块处理完之后）响应取消，
     * 并顺手清掉这次已经写进去的半成品数据。所以本方法返回的只是「取消已受理」时的任务快照，
     * 状态可能还是运行中，前端仍需继续轮询直到它变成已取消。</p>
     *
     * <p>会写数据库（写入取消标记和原因）。主要失败情形：任务已经结束因此不能再取消、角色无权操作。</p>
     */
    @PostMapping("/ingest-tasks/{taskId}/cancel")
    public Response<RagIngestTaskResponseDTO> cancel(@PathVariable String taskId,
                                                      @RequestBody(required = false)
                                                      // 请求体允许缺省：不填就是不带取消原因，取消动作本身照常受理。
                                                      RagIngestTaskCancelRequestDTO request) {
        // 任务已结束或无权操作都是可预期拒绝，统一接住转成错误码。
        try {
            // 带着可信身份、角色、任务和可选原因请求取消，并把当时的任务快照返回给前端继续轮询。
            return success(taskResponse(managementService.cancelTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId,
                    request == null ? null : request.getReason())));
        } catch (AppException e) {
            // 任务不可取消时返回业务错误码，前端应刷新任务状态而不是反复点取消。
            return failure(e);
        }
    }

    /**
     * 把一个失败或已进死信的摄取任务重新放回摄取链路。
     *
     * <p>重试不是从头再来：任务停在哪个 checkpoint 就从哪里继续，已经处理完的分块不会重复向量化，
     * 既省钱也避免重复写入。领域层会检查重试次数是否已达上限。</p>
     *
     * <p>会写数据库（重置任务状态并重新排队）。主要失败情形：任务不处于可重试状态、重试次数超限、角色无权操作。</p>
     */
    @PostMapping("/ingest-tasks/{taskId}/retry")
    public Response<RagIngestTaskResponseDTO> retry(@PathVariable String taskId) {
        // 不可重试或次数超限都是可预期拒绝，统一接住转成错误码。
        try {
            // 交给领域层重置任务并重新入队，返回新的任务快照供前端继续轮询。
            return success(taskResponse(managementService.retryTask(TenantContextHolder.getTenantId(),
                    TenantContextHolder.getUserId(), TenantContextHolder.getRoleCode(), taskId)));
        } catch (AppException e) {
            // 不可重试时返回业务错误码，提示用户联系管理员而不是反复点重试。
            return failure(e);
        }
    }

    /**
     * 把文档领域实体翻成对外 DTO。
     *
     * <p>关键是两个 generation：activeGeneration 是当前真正参与检索的那一代内容，
     * targetGeneration 是正在构建的目标代。两者不相等就说明这份文档正在重建索引，
     * 前端据此显示「更新中」而不是直接当成已就绪。</p>
     *
     * <p>状态枚举统一转小写作为稳定的 API 取值。不查库、不改状态，纯结构转换。</p>
     */
    private RagDocumentResponseDTO documentResponse(RagDocumentEntity value) {
        // 逐字段搬运：文档身份与展示名、状态、两个 generation 与激活版本、乐观锁版本，
         // 以及页数和分块数（前端用它显示内容规模）。
        return RagDocumentResponseDTO.builder().documentId(value.documentId())
                .knowledgeBaseId(value.knowledgeBaseId()).displayName(value.displayName())
                .status(value.status().name().toLowerCase(java.util.Locale.ROOT))
                .activeVersionId(value.activeVersionId()).activeGeneration(value.activeGeneration())
                .targetGeneration(value.targetGeneration()).revision(value.revision())
                .pageCount(value.pageCount()).chunkCount(value.chunkCount()).build();
    }

    /**
     * 把摄取任务实体和它的 checkpoint 摊平成前端能直接渲染的进度。
     *
     * <p>checkpoint 在领域层是嵌套结构，记着「当前阶段、已处理多少分块」。这里摊到同一层，
     * 并把操作类型、阶段、状态三个枚举都转成小写字符串，前端不必了解领域模型的层次。</p>
     *
     * <p>不查库、不改状态，纯结构转换；入参为空会抛空指针，调用方必须先确认有值。</p>
     */
    private RagIngestTaskResponseDTO taskResponse(RagIngestJobEntity value) {
        // 逐字段搬运：任务与文档版本身份、操作类型与当前阶段、状态、分块进度、重试次数、
         // 失败码与取消原因，最后带上乐观锁版本。
        return RagIngestTaskResponseDTO.builder().taskId(value.jobId())
                .knowledgeBaseId(value.knowledgeBaseId()).documentId(value.documentId())
                .versionId(value.versionId()).operation(value.operation().name().toLowerCase(java.util.Locale.ROOT))
                .stage(value.checkpoint().stage().name().toLowerCase(java.util.Locale.ROOT))
                .status(value.status().name().toLowerCase(java.util.Locale.ROOT))
                .processedChunks(value.checkpoint().processedChunks()).totalChunks(value.checkpoint().totalChunks())
                .attemptCount(value.attemptCount()).maxAttempts(value.maxAttempts())
                .errorCode(value.errorCode()).cancelReason(value.cancelReason()).revision(value.revision()).build();
    }

    /** 用统一的成功码和文案包装数据，让所有接口的成功响应结构一致，前端只需写一套解析逻辑。 */
    private <T> Response<T> success(T data) {
        // 成功码 + 成功文案 + 业务数据，三段固定结构。
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 把领域层抛出的业务异常原样翻译成响应：错误码和文案都是设计好的，可直接展示给用户，不带 data。 */
    private <T> Response<T> failure(AppException error) {
        // 只回错误码和文案，前端据此提示具体原因。
        return Response.<T>builder().code(error.getCode()).info(error.getInfo()).build();
    }
}
