package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagUploadRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadResult;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagUploadRegistration;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagObjectStorageScope;
import cn.bugstack.ai.domain.rag.model.valobj.RagValidatedUploadFile;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 接收管理员上传的文档，把原文件放进对象存储，并登记一条待摄取的任务。
 *
 * <p>解决什么问题：一次上传要同时改动两个系统——对象存储（放原文件）和数据库（登记文档、版本、任务）。
 * 这两者没法放进一个事务，所以必须约定一个安全顺序：先写对象存储，再登记数据库；
 * 数据库登记失败就把刚上传的文件删掉（补偿）。反过来做就会出现「数据库说有文档，实际文件不存在」，
 * 那种脏数据会让摄取任务永久失败且无法自愈。</p>
 *
 * <p>幂等怎么做：用「租户 + 知识库 + 文件内容哈希」算出一个幂等键。同一份文件重复上传会命中同一个键，
 * 此时不新建文档，而是把已有任务原样返回并标记为去重命中，同时把这次多上传的那份文件删掉。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用编排服务。只受理，不做真正的解析、切片和向量化。</p>
 *
 * <p>谁会调用它：文档管理的 HTTP 控制器（管理员在界面上传文件）。</p>
 *
 * <p>它向下调用什么：RAG 仓储（查知识库、按幂等键查任务）、上传登记端口（在一个事务里原子写入
 * 文档 + 版本 + 摄取任务 + Outbox 事件）、对象存储服务（放文件、删文件）、
 * 文件安全策略（校验类型与大小、生成安全文件名）、知识库授权服务（要求管理员）。</p>
 *
 * <p>它不负责什么：不解析文档、不清洗、不切片、不生成向量、不激活索引代次——这些全部由后台摄取 Worker
 * 按登记好的任务异步执行。上传成功只代表「已受理」，不代表文档已经可以被检索到。</p>
 */
@Service
public class RagDocumentUploadService {

    /**
     * 摄取任务失败后允许自动重试的默认次数，3 次。
     *
     * <p>写进任务账本，由后台 Worker 遵守。取 3 是因为大多数失败来自临时性原因（下载抖动、解析服务重启）；
     * 真正的格式问题重试再多次也不会成功，留给人工处理更合适。</p>
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /**
     * RAG 主仓储，这里只用来读：查知识库是否存在且可接收文档、按幂等键查是否已有相同内容的任务。
     *
     * <p>查询都带租户号，跨租户读不到任何东西。真正的写入不走它，而走原子登记端口。</p>
     */
    private final IRagRepository repository;
    /**
     * 上传登记端口，负责在一个事务里原子写入文档、版本、摄取任务和 Outbox 事件。
     *
     * <p>四者必须同生共死：只写文档不写任务，文档会永远停在处理中；只写任务不写 Outbox，
     * Worker 可能永远收不到通知。返回 false 表示唯一约束冲突，也就是并发的另一次上传抢先登记成功了。</p>
     */
    private final RagUploadRegistrationPort registrationPort;
    /**
     * 对象存储服务，负责把原文件流式写入 RAG 专用桶，并在登记失败时删除。
     *
     * <p>写入结果里带回真实的桶名、对象键、字节数和内容哈希；内容哈希是后面算幂等键的关键输入，
     * 必须用存储侧算出的值而不是客户端声称的值，否则客户端可以伪造哈希绕过去重。</p>
     */
    private final ObjectStorageService objectStorageService;
    /**
     * 上传文件的安全策略，负责校验类型、大小，并生成清理过的安全文件名。
     *
     * <p>直接 new 出来使用：它是无状态的纯规则对象，没有任何外部依赖，不需要 Spring 管理。
     * 校验必须在写对象存储之前完成，否则恶意文件会先落盘再被拒。</p>
     */
    private final RagUploadFilePolicy filePolicy = new RagUploadFilePolicy();
    /**
     * 知识库授权服务，要求上传者是该知识库所属租户的管理员。
     *
     * <p>同样直接 new：它是无状态的判断规则。注意本项目其他服务是通过构造注入拿到它的，
     * 这里用 new 属于风格不一致，但行为完全等价（该类无字段、无依赖）。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorizationService =
            new RagKnowledgeBaseAuthorizationService();

    /**
     * 由 Spring 注入仓储、原子登记端口和对象存储；文件策略与授权服务在字段上直接构造。
     */
    public RagDocumentUploadService(IRagRepository repository,
                                    RagUploadRegistrationPort registrationPort,
                                    ObjectStorageService objectStorageService) {
        // 保存仓储引用，用于查知识库和查幂等任务。
        this.repository = repository;
        // 保存原子登记端口引用，所有数据库写入都经它在一个事务内完成。
        this.registrationPort = registrationPort;
        // 保存对象存储引用，用于写入原文件以及失败时的补偿删除。
        this.objectStorageService = objectStorageService;
    }

    /**
     * 受理一次文档上传：校验、存文件、登记任务，失败时清理已上传的文件。
     *
     * <p>各层职责：
     * 第一层：命令非空兜底；
     * 第二层：读知识库，做管理员与租户归属校验，并确认知识库当前允许接收新文档；
     * 第三层：文件安全校验（类型、大小、文件名），必须在写存储之前完成；
     * 第四层：生成本次上传涉及的四个编号和目标索引代次，拼出对象存储路径；
     * 第五层：把文件写进对象存储，拿到存储侧算出的内容哈希；
     * 第六层：用租户 + 知识库 + 内容哈希算幂等键，命中已有任务则删掉这次的文件并返回已有结果；
     * 第七层：构造文档、版本、摄取任务三个实体，原子登记；登记失败说明并发抢先，同样删文件并返回对方结果；
     * 第八层：任何运行时异常都先删掉已上传的文件再往外抛，绝不留下没人认领的孤儿文件。</p>
     *
     * <p>数据流：
     * 上传命令（租户 + 用户 + 角色 + 知识库 + 临时文件）
     * → 知识库存在性与管理员校验
     * → 知识库可接收状态校验
     * → 文件安全校验（得到安全文件名、大小、类型）
     * → 生成文档号 / 版本号 / 任务号 / 事件号 + 目标代次
     * → 拼对象键 → 写对象存储 → 得到内容哈希
     * → 算幂等键 → 查已有任务
     * → 命中：删本次文件 → 返回已有任务（标记去重）
     * → 未命中：构造文档 + 版本 + 任务 → 原子登记（含 Outbox 事件）
     * → 登记冲突：删本次文件 → 读并发者任务返回
     * → 登记成功：返回排队中的受理结果
     * → 任何异常：删本次文件后原样抛出</p>
     *
     * <p>返回结果：文档号、版本号、任务号、安全文件名、大小、状态、是否去重命中。状态为 queued 表示已受理待处理，
     * 不代表文档已经可检索。</p>
     *
     * <p>会写对象存储和数据库，并通过 Outbox 触发后台摄取。主要失败条件：命令为空、知识库不存在或不可用、
     * 非管理员、文件不合法、对象存储写入失败、登记失败且补偿删除也失败。</p>
     *
     * <p>为什么先写存储后写库：反过来的话，数据库先记下「有这份文档」而文件还没落盘，一旦写存储失败，
     * 数据库里就留下一条永远处理不了的记录。当前顺序的最坏结果只是留下一个孤儿文件，
     * 而这个风险又被补偿删除进一步压掉了。</p>
     */
    public RagDocumentUploadResult upload(RagDocumentUploadCommand command) {
        // 第一层：命令为空说明调用方用法有问题，直接拒绝，不做任何存储或数据库操作。
        if (command == null) throw new AppException("RAG_UPLOAD_INVALID", "上传命令不能为空");
        // 第二层：先按租户查知识库；查不到或不属于本租户都统一按「不存在或无权访问」抛错，不暴露存在性。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(command.tenantId(),
                        command.knowledgeBaseId())
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        // 再校验上传者是本租户管理员且这个库确实归当前租户管；普通成员不能往知识库里塞文档。
        authorizationService.requireManageable(command.tenantId(), command.userId(), command.roleCode(), knowledgeBase);
        // 知识库还要处于可检索状态才允许接收新文档：删除中、正在重建索引、被停用的库都不能收。
        if (!knowledgeBase.status().searchable()) {
            // 状态不允许就直接拒绝，避免把文档挂到一个即将被清理或正在重建的库上，产生对不上的中间状态。
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "知识库当前不能接收新文档");
        }
        // 第三层：文件安全校验，检查类型和大小并生成清理过的安全文件名。
        // 必须排在写对象存储之前：否则一个超大或危险类型的文件会先落盘，再被拒绝，白占存储和带宽。
        RagValidatedUploadFile file = filePolicy.validate(command.file());

        // 第四层：预先生成逻辑文档编号。文档是「同一份资料的多个版本」的容器。
        String documentId = id("doc");
        // 生成本次上传对应的版本编号。版本一旦创建就不可变，重新上传会产生新版本而不是改旧版本。
        String versionId = id("ragver");
        // 生成摄取任务编号，前端拿它轮询处理进度。
        String taskId = id("ragtask");
        // 生成 Outbox 事件编号，用于可靠通知后台 Worker 有新任务；事件与任务在同一事务里落库，不会丢通知。
        String eventId = id("ragevt");
        // 计算目标索引代次：至少从 1 开始。空库的当前代次是 0，而代次必须大于 0 才合法，
        // 所以这里用 max 兜底，保证第一份文档也能拿到一个有效代次。
        long generation = Math.max(1L, knowledgeBase.currentGeneration());
        // 拼出对象存储路径：租户 / 知识库 / 文档 / 版本 / 安全文件名。
        // 路径里带租户和知识库，是为了让存储层也具备天然的隔离结构，误配的权限不会让人扫到别人的文件。
        String objectKey = RagObjectStorageScope.sourceObjectKey(command.tenantId(), command.knowledgeBaseId(), documentId,
                versionId, file.safeFileName());
        // 第五层：把临时文件流式写入 RAG 专用桶，并带上大小和内容类型。
        // 返回结果里的内容哈希由存储侧真实计算，后面算幂等键只认这个值，不采信客户端声称的哈希。
        ObjectStorageResultEntity stored = objectStorageService.putFile(ObjectStorageFileCommandEntity.builder()
                .bucket(objectStorageService.ragBucket()).objectKey(objectKey).sourcePath(file.path())
                .sizeBytes(file.sizeBytes()).contentType(file.mimeType()).build());
        // 第六层：用「固定前缀 + 租户 + 知识库 + 内容哈希」算幂等键。
        // 带租户和知识库是关键：同一份文件被不同租户上传、或传进同一租户的不同知识库，都应各自独立建文档，
        // 只有「同租户同知识库同内容」才算重复上传。
        String idempotencyKey = sha256("ingest\n" + command.tenantId() + "\n"
                + command.knowledgeBaseId() + "\n" + stored.getSha256());
        // 从这里开始文件已经落盘了。整段包在 try 里，是为了保证后面任何一步失败都能把这个文件删掉，
        // 不留下没有任何数据库记录指向的孤儿文件。
        try {
            // 按幂等键查是否已有相同内容的摄取任务。
            var existing = repository.findIngestJobByIdempotencyKey(command.tenantId(), idempotencyKey);
            // 命中说明这份文件之前已经传过（可能仍在处理，也可能早已完成）。
            if (existing.isPresent()) {
                // 已有任务就不需要这次上传的副本了，立刻删掉，避免同一份内容在存储里堆积多份。
                compensate(stored);
                // 把已有任务映射成上传响应并标记去重命中，前端据此提示「该文件已存在」而不是新建一条。
                return existingResult(existing.get(), file, true);
            }
            // 第七层：构造逻辑文档实体。可见性默认租户共享；活跃版本先留空、活跃代次先置 0，
            // 因为此刻还没有任何版本处理成功；状态置为处理中，让界面显示「正在解析」；revision 从 0 开始。
            RagDocumentEntity document = new RagDocumentEntity(command.tenantId(), command.userId(),
                    RagVisibility.TENANT, command.knowledgeBaseId(), documentId, file.safeFileName(),
                    null, 0L, generation, RagDocumentStatus.PROCESSING, 0L);
            // 构造不可变的版本实体：版本号从 1 开始，带上目标代次、真实桶名与对象键、安全文件名、
            // 存储侧算出的内容哈希、内容类型和字节数；状态置为排队中，等 Worker 领取。
            // 中间几个 null 是解析产物（清洗后对象键、IR 对象键等）的占位，由 Worker 处理完再回填。
            RagDocumentVersionEntity version = new RagDocumentVersionEntity(command.tenantId(),
                    command.knowledgeBaseId(), documentId, versionId, 1, generation, stored.getBucket(),
                    stored.getObjectKey(), null, null, file.safeFileName(), stored.getSha256(), file.mimeType(),
                    stored.getSizeBytes(), RagDocumentVersionStatus.QUEUED, null, null, null, 0L);
            // 构造待执行的摄取任务：绑定文档与版本、带上幂等键（数据库对它有唯一约束，这是防重复的最后一道闸）、
            // 操作类型为摄取、目标代次、以及允许的最大重试次数。
            RagIngestJobEntity job = RagIngestJobEntity.pending(command.tenantId(), command.knowledgeBaseId(),
                    documentId, versionId, taskId, idempotencyKey, RagIngestOperation.INGEST,
                    generation, DEFAULT_MAX_ATTEMPTS);
            // 把文档、版本、任务、Outbox 事件打包成一次原子登记。四者同一事务，要么全成功要么全不写，
            // 不会出现「有文档没任务」或「有任务没通知」这类无法自愈的半成品。
            boolean inserted = registrationPort.register(command.tenantId(),
                    new RagUploadRegistration(document, version, job, eventId));
            // 登记失败只有一种原因：并发的另一次上传用同一个幂等键先登记成功了（唯一约束冲突）。
            if (!inserted) {
                // 本次上传的文件已经没有归属了，立刻删掉。
                compensate(stored);
                // 再按幂等键把并发获胜者的任务读出来。
                RagIngestJobEntity winner = repository.findIngestJobByIdempotencyKey(command.tenantId(),
                                idempotencyKey)
                        // 读不到属于罕见情形（对方事务刚提交、当前连接还看不到），此时明确提示稍后查询，
                        // 绝不重新建一份文档，否则同一份内容会出现两条记录。
                        .orElseThrow(() -> new AppException("RAG_UPLOAD_CONCURRENT_RESULT_MISSING",
                                "并发上传已受理但任务暂不可见，请稍后查询"));
                // 把获胜者的任务返回并标记去重，对调用方来说结果和自己受理成功一样。
                return existingResult(winner, file, true);
            }
            // 登记成功，返回本次受理结果：状态固定为 queued，明确告诉前端「已收下，正在排队处理」，
            // 去重标记为 false 表示这是一份新文档。
            return new RagDocumentUploadResult(documentId, versionId, taskId, file.safeFileName(),
                    file.sizeBytes(), "queued", false);
        // 第八层：兜住所有运行时异常（登记端口报错、数据库超时、构造实体时参数非法等）。
        } catch (RuntimeException error) {
            // 无论什么原因失败，先把已经上传的文件删掉，保证存储里不留孤儿对象。
            compensate(stored);
            // 原样抛出原始异常，不做转换：调用方需要看到真实失败原因，补偿只是顺手做的清理。
            throw error;
        }
    }

    /**
     * 把一条已有的摄取任务映射成上传响应，不新建任何文档。
     *
     * <p>用于两条去重路径：幂等键命中已有任务，以及并发登记冲突后读到对方的任务。
     * 文件名和大小取自本次上传的文件（内容相同，所以这两个值也一致），其余标识全部取自已有任务，
     * 保证前端拿到的编号指向的是真正在处理的那一条。</p>
     *
     * <p>纯转换，不写库、不调外部服务。</p>
     */
    private RagDocumentUploadResult existingResult(RagIngestJobEntity job, RagValidatedUploadFile file,
                                                    boolean deduplicated) {
        // 组装响应：文档号、版本号、任务号都取自已有任务；文件名和大小取自本次文件；
        // 状态由任务的当前状态转小写得到（用固定语区，避免不同语区下大小写转换结果不一致）；
        // 最后带上去重标记，让前端能明确提示这是一次重复上传。
        return new RagDocumentUploadResult(job.documentId(), job.versionId(), job.jobId(), file.safeFileName(),
                file.sizeBytes(), job.status().name().toLowerCase(java.util.Locale.ROOT), deduplicated);
    }

    /**
     * 补偿：数据库没有受理这次上传时，把已经写进对象存储的文件删掉。
     *
     * <p>为什么必须做：文件先落盘、数据库后登记，一旦登记失败，这个文件就没有任何记录指向它，
     * 既不会被检索也不会被清理，会永久占用存储。</p>
     *
     * <p>为什么清理失败要抛专门的错误码：这时候既没登记成功、又留下了垃圾文件，属于必须人工介入的状态。
     * 静默忽略会让垃圾无声堆积，所以显式换成一个可告警的错误码抛出去。</p>
     *
     * <p>会调用外部存储服务（删除操作）。</p>
     */
    private void compensate(ObjectStorageResultEntity stored) {
        // 删除本身也可能失败（网络、权限、存储侧异常），所以要单独捕获处理。
        try {
            // 按真实桶名和对象键删除，这两个值都取自写入结果而不是拼出来的，保证删的就是刚写的那个对象。
            objectStorageService.deleteObject(stored.getBucket(), stored.getObjectKey());
        // 删除失败：此刻登记也没成功，系统里留下了一个没有归属的文件。
        } catch (RuntimeException cleanupError) {
            // 换成专用错误码抛出，并带上原始异常，便于告警和后台清理任务定位；绝不静默吞掉。
            throw new AppException("RAG_UPLOAD_COMPENSATION_FAILED",
                    "上传登记失败且对象清理失败，需要后台清理", cleanupError);
        }
    }

    /**
     * 计算摄取幂等键的摘要。
     *
     * <p>输入是「固定前缀 + 租户 + 知识库 + 文件内容哈希」，所以同租户同知识库的同一份内容永远算出同一个键。
     * 数据库对这个键建唯一约束，重复上传和并发上传都会被它挡下来。</p>
     */
    private String sha256(String value) {
        // SHA-256 是 JDK 必备算法，实际不会缺失，但接口签名要求处理异常，所以包一层。
        try {
            // 按 UTF-8 取字节算摘要再转十六进制；显式指定编码，保证含中文的输入在任何环境算出同一个键。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        // 走到这里说明运行环境连 SHA-256 都没有，属于 JVM 被破坏，不是业务能处理的情况。
        } catch (NoSuchAlgorithmException e) {
            // 直接抛非法状态异常终止，绝不退化成弱哈希——幂等键一旦不可靠，重复上传就再也拦不住了。
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 生成带业务前缀的随机标识。
     *
     * <p>前缀让日志里一眼看出这是文档、版本、任务还是事件；随机部分用去掉横线的 UUID，
     * 保证外部无法猜测或枚举这些编号。</p>
     */
    private String id(String prefix) {
        // 前缀 + 下划线 + 去横线的随机 UUID；去横线是为了让编号在 URL 和日志里更紧凑。
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
