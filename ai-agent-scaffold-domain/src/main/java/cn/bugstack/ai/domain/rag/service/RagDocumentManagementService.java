package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 管理员查看文档与摄取任务，以及取消、重试摄取任务的入口。
 *
 * <p>解决什么问题：文档上传之后要经过下载、解析、清洗、切片、向量化、激活索引等一长串异步步骤，
 * 中途可能卡住、失败，管理员需要能看进度、能中止、能重试。难点在于这些任务可能正被某台后台 Worker
 * 持有租约（lease）在跑，此时不能由这里直接改成终态——Worker 那边的外部副作用（半写的向量、临时文件）
 * 必须由它自己清理。所以取消分成两条路：没被领取的任务当场同步关闭，正在被跑的任务只立一个取消屏障，
 * 由 Worker 看到屏障后自行收尾。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用服务，写操作都开事务。</p>
 *
 * <p>谁会调用它：文档与任务管理的 HTTP 控制器（管理员在界面看列表、看进度、点取消、点重试）。</p>
 *
 * <p>它向下调用什么：RAG 仓储（读文档 / 版本 / 任务，CAS 更新任务，原子取消与重新排队）、
 * 知识库授权服务（每个入口都要求管理员），以及一个可注入的时钟。</p>
 *
 * <p>它不负责什么：不执行摄取、不清理向量与对象存储（那是 Worker 的事）、不删除文档
 * （删除由 RagDocumentDeletionService 受理）、不做租户身份解析。响应层还必须继续隐藏任务上的
 * 租约持有者和 fencing token 等内部字段，这些不该暴露给前端。</p>
 */
@Service
public class RagDocumentManagementService {

    /**
     * RAG 仓储，负责读取文档 / 版本 / 任务，以及带 revision 条件的 CAS 更新和跨表原子操作。
     *
     * <p>所有查询都带租户号，跨租户既读不到也改不了。</p>
     */
    private final IRagRepository repository;
    /**
     * 知识库授权服务，每个公开入口都先经它确认调用者是本租户管理员且这个库归本租户。
     *
     * <p>连查任务都要求管理员，因为任务里带有解析进度、失败原因等内部信息。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorizationService;
    /**
     * 时钟，取消任务时用它取当前时间写进终态记录。
     *
     * <p>做成可注入字段而不是直接调 System.currentTimeMillis()，是为了让「取消时间」在测试里可控可复现；
     * 生产环境注入的是 UTC 系统时钟。</p>
     */
    private final Clock clock;

    /**
     * 生产环境使用的构造方法，由 Spring 调用，时钟固定为 UTC 系统时钟。
     *
     * <p>用 UTC 而不是本地时区，是为了让多地部署的节点写进数据库的时间语义一致。</p>
     */
    @Autowired
    public RagDocumentManagementService(IRagRepository repository,
                                        RagKnowledgeBaseAuthorizationService authorizationService) {
        // 转调完整构造方法并补上 UTC 时钟，保证生产与测试走同一套初始化逻辑。
        this(repository, authorizationService, Clock.systemUTC());
    }

    /**
     * 完整构造方法，包内可见，专供测试注入固定时钟。
     *
     * <p>刻意不加 public：外部不应该自己决定这个服务用什么时钟，否则生产环境可能被注入一个错误时钟。</p>
     */
    RagDocumentManagementService(IRagRepository repository,
                                 RagKnowledgeBaseAuthorizationService authorizationService,
                                 Clock clock) {
        // 保存仓储引用，用于读写文档、版本和任务。
        this.repository = repository;
        // 保存授权服务引用，每个入口先做管理员校验。
        this.authorizationService = authorizationService;
        // 保存时钟引用，取消任务时用它取当前时间。
        this.clock = clock;
    }

    /**
     * 列出某个知识库下管理员可维护的全部文档。
     *
     * <p>先做「读知识库 + 管理员 + 租户归属」三合一校验，通过后才按租户列文档。
     * 越权和跨租户请求都会在校验阶段统一按「知识库不存在」挡掉，不暴露资源存在性。</p>
     *
     * <p>只读，不写库。</p>
     */
    public List<RagDocumentEntity> listDocuments(String tenantId, String userId, String roleCode,
                                                  String knowledgeBaseId) {
        // 先完成读知识库与管理员校验；权限判断永远排在数据读取之前。
        requireManageable(tenantId, userId, roleCode, knowledgeBaseId);
        // 校验通过后按租户和知识库列出文档；租户隔离由查询条件保证。
        return repository.listDocuments(tenantId, knowledgeBaseId);
    }

    /**
     * 按任务编号查一个摄取任务，查到之后再反查知识库复核管理员权限。
     *
     * <p>数据流：租户 + 任务编号 → 查任务 → 取出知识库编号 → 读知识库并做管理员校验 → 返回任务</p>
     *
     * <p>为什么要「先查任务再鉴权」：任务编号是随机串，但不能靠猜不到来当安全保障。
     * 权限必须挂在真实资源上，所以要顺着任务找回它所属的知识库，再确认这个人能管它。</p>
     *
     * <p>只读，不写库。返回的实体里带有租约和 fencing token，响应层必须把它们过滤掉再返回前端。</p>
     */
    public RagIngestJobEntity requireTask(String tenantId, String userId, String roleCode, String taskId) {
        // 按租户查任务；租户号和任务编号都先做非空校验，避免拿空值去查库。
        RagIngestJobEntity task = repository.findIngestJob(requireText(tenantId, "tenantId"),
                        requireText(taskId, "taskId"))
                .orElseThrow(() -> new AppException("RAG_INGEST_TASK_NOT_FOUND", "摄取任务不存在或无权访问"));
        // 用任务里记的知识库编号反查知识库并做管理员校验；越权者即使拿到任务编号也会在这里被挡掉。
        requireManageable(tenantId, userId, roleCode, task.knowledgeBaseId());
        // 权限确认后才返回任务实体。
        return task;
    }

    /**
     * 列出某个知识库最近的若干条摄取任务，供页面刷新后恢复进度显示。
     *
     * <p>数据流：租户 + 知识库编号 + 数量上限 → 管理员校验 → 数量区间校验 → 按租户查任务列表</p>
     *
     * <p>数量必须落在 1 到 200 之间：下限保证请求有意义，上限防止一次拉出海量任务把接口和界面压垮。</p>
     *
     * <p>只读，不写库。</p>
     */
    public List<RagIngestJobEntity> listTasks(String tenantId, String userId, String roleCode,
                                              String knowledgeBaseId, int limit) {
        // 先做读知识库与管理员校验，越权请求拿不到任何任务信息。
        requireManageable(tenantId, userId, roleCode, knowledgeBaseId);
        // 数量上限必须在合理区间：小于 1 没有意义，大于 200 会一次拉出过多任务，拖慢接口也撑坏界面。
        if (limit < 1 || limit > 200) {
            // 越界直接拒绝，而不是自动截断——截断会让管理员误以为「就这么多任务」。
            throw new AppException("RAG_TASK_LIMIT_INVALID", "任务查询数量必须在1到200之间");
        }
        // 按租户和知识库查最近的任务，条数由仓储按上限截取。
        return repository.listIngestJobs(tenantId, knowledgeBaseId, limit);
    }

    /**
     * 请求取消一个摄取任务。
     *
     * <p>各层职责：
     * 第一层：查任务并做管理员校验；
     * 第二层：任务已是已取消终态时，走一次状态对账（修复历史上「任务取消了但文档 / 版本没同步」的中间态）；
     * 第三层：还没立取消屏障的，先把任务改成「已请求取消」并回读最新状态；
     * 第四层：任务仍被 Worker 持有租约时就此返回——外部副作用只能由持有租约的 Worker 自己清理；
     * 第五层：没有租约的任务当场同步关闭，把任务、版本、文档三者在一个原子操作里推到取消终态。</p>
     *
     * <p>数据流：
     * 管理员取消请求（任务编号 + 原因）
     * → 查任务 + 管理员校验
     * → 已取消？→ 状态对账 → 返回
     * → 未立屏障？→ 改成已请求取消（revision CAS）→ 回读最新任务
     * → 仍有租约？→ 直接返回，等 Worker 自行收尾
     * → 无租约 → 取当前时间 → 标记已取消 → 读版本与文档
     * → 原子取消（任务 + 版本 + 文档三者的 revision 同时作为条件）
     * → 回读任务返回</p>
     *
     * <p>为什么运行中的任务不能在这里直接改成终态：Worker 可能正在往向量库写数据、正在写临时文件。
     * 这边把任务标成已取消，Worker 那边还在继续写，最后就留下一堆没人认领的向量和文件。
     * 立屏障让 Worker 自己发现并收尾，是唯一能保证副作用被清干净的做法。</p>
     *
     * <p>会写数据库并开事务。重复取消是安全的：已取消走对账，已请求取消跳过屏障设置。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagIngestJobEntity cancelTask(String tenantId, String userId, String roleCode,
                                         String taskId, String reason) {
        // 第一层：查任务并完成管理员校验，越权请求到这里就结束了。
        RagIngestJobEntity current = requireTask(tenantId, userId, roleCode, taskId);
        // 第二层：任务已经是已取消终态。
        if (current.status() == RagIngestJobStatus.CANCELLED) {
            // 走一次对账：历史上可能出现任务取消了但文档或版本状态没跟上的中间态，顺手修一下再返回。
            return reconcileUnclaimedCancellation(tenantId, current);
        }
        // 第三层：还没立取消屏障（状态不是已请求取消），需要先把屏障立起来。
        if (current.status() != RagIngestJobStatus.CANCEL_REQUESTED) {
            // 生成带取消原因的新状态；原因会写进任务，方便事后知道是谁为什么取消的。
            RagIngestJobEntity requested = current.requestCancel(reason);
            // 用当前 revision 做 CAS 更新，受影响行数不是 1 就抛并发冲突——说明期间 Worker 或别人改过任务。
            requireUpdated(repository.updateIngestJob(tenantId, requested, current.revision()));
            // 立完屏障必须回读最新任务：这一步之后要判断租约，而租约状态可能刚刚被 Worker 改过，
            // 用内存里的旧对象判断会做出错误决定。读不到属于异常，直接报任务不可见。
            current = repository.findIngestJob(tenantId, taskId)
                    .orElseThrow(() -> new AppException("RAG_INGEST_TASK_NOT_FOUND", "取消后的任务不可见"));
        }
        // 第四层：任务仍被某台 Worker 持有租约，说明它正在跑。
        // 此时到此为止，只留下取消屏障；由那台 Worker 自己发现屏障并清理它已经产生的外部副作用。
        if (current.lease() != null) return current;
        // 第五层：没有租约，可以当场同步关闭。取一次当前时间作为取消完成时间。
        Instant now = clock.instant();
        // 生成已取消终态：租约持有者传 null（本来就没人持有），沿用原 fencing token，带上取消时间。
        RagIngestJobEntity cancelled = current.markCancelled(null, current.fencingToken(), now);
        // 读出这个任务对应的版本，取消要把它也推到终态。
        RagDocumentVersionEntity version = requireVersion(tenantId, current.versionId());
        // 读出文档，取消同样要清掉它上面「正在往哪一代索引写」的目标代次标记。
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        // 在一个原子操作里同时推进任务、版本、文档三者，并把三者各自的 revision 都作为 CAS 条件。
        // 三者必须一起成功：只改任务不改版本，版本会永远停在排队中；不清文档的目标代次，
        // 后续重试会以为还有一次摄取正在进行。
        repository.cancelUnclaimedIngestJob(tenantId, cancelled, current.revision(),
                version.revision(), document.revision());
        // 回读任务返回最新状态；读不到就退回内存里那份已取消对象，保证接口一定有返回值。
        return repository.findIngestJob(tenantId, taskId).orElse(cancelled);
    }

    /**
     * 重新执行一个失败或死信的任务。
     *
     * <p>各层职责：
     * 第一层：查任务并做管理员校验；
     * 第二层：状态准入——只有失败和死信任务可以重试，正在跑的不能重复触发；
     * 第三层：按操作类型分流，重建链路尚未实现直接拒绝，删除任务走专门的恢复逻辑；
     * 第四层：摄取任务要额外确认知识库当前可用（删除中或重建中的库不能再往里写）；
     * 第五层：读版本与文档，并核对任务、版本、文档三者的资源范围和代次完全一致；
     * 第六层：把任务、版本、文档三者一起推回「待处理」状态，用四个 revision 做原子 CAS。</p>
     *
     * <p>数据流：
     * 管理员重试请求（任务编号）
     * → 查任务 + 管理员校验
     * → 状态准入（仅失败 / 死信）
     * → 操作类型分流（重建拒绝 / 删除转专用逻辑）
     * → 读知识库并校验可用
     * → 读版本与文档 → 范围与代次一致性校验
     * → 任务转重新排队 + 版本转排队中 + 文档转处理中
     * → 原子重新排队（任务 / 版本 / 文档 / 知识库四个 revision 同时作为条件）
     * → 回读任务返回</p>
     *
     * <p>为什么不依赖消息投递：重新排队只写数据库，由数据库的到期扫描去唤醒任务。
     * 这样即使消息中间件抖动或消息丢了，重试也一定会发生，不会出现「点了重试但永远没人跑」。</p>
     *
     * <p>会写数据库并开事务。主要失败条件：非管理员、任务状态不允许重试、操作类型不支持、
     * 知识库不可用、任务与文档版本范围不一致、并发冲突。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagIngestJobEntity retryTask(String tenantId, String userId, String roleCode, String taskId) {
        // 第一层：查任务并完成管理员校验。
        RagIngestJobEntity current = requireTask(tenantId, userId, roleCode, taskId);
        // 第二层：只有失败和死信任务可以重试。排队中和运行中的任务重复触发会产生两份并发执行，
        // 已完成的任务重跑则会白白重建一遍索引。
        if (current.status() != RagIngestJobStatus.FAILED && current.status() != RagIngestJobStatus.DEAD) {
            // 状态不允许就拒绝，并明确说明只有失败或死信可以重试。
            throw new AppException("RAG_INGEST_RETRY_STATE_INVALID", "只有失败或死信任务可以重新执行");
        }
        // 第三层：按操作类型分流。重建链路的语义与摄取完全不同，不能共用同一套恢复逻辑。
        if (current.operation() == RagIngestOperation.REBUILD) {
            // 知识库重建链路还没实现，明确拒绝而不是走摄取逻辑——走错分支会把数据改成不一致状态。
            throw new AppException("RAG_REBUILD_NOT_IMPLEMENTED", "知识库重建链路尚未实现");
        }
        // 删除任务的恢复条件和摄取完全不同（要看文档墓碑，而不是看知识库可用性）。
        if (current.operation() == RagIngestOperation.DELETE) {
            // 转给删除专用恢复逻辑，处理完直接返回，不再走后面的摄取分支。
            return retryDelete(tenantId, current);
        }
        // 第四层：摄取任务还要读一次知识库并做管理员校验（顺带拿到它的 revision 供后面 CAS 使用）。
        RagKnowledgeBaseEntity knowledgeBase = requireManageable(
                tenantId, userId, roleCode, current.knowledgeBaseId());
        // 知识库必须仍处于可检索状态：删除中、正在重建索引、被停用的库不能再接收摄取写入，
        // 否则写进去的向量要么马上被清掉，要么落在一个作废的代次里。
        if (!knowledgeBase.status().searchable()) {
            // 库不可用就拒绝恢复，让管理员先把库处理好。
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "当前知识库不能恢复摄取任务");
        }
        // 第五层：读出任务对应的版本，重试要把它推回排队中。
        RagDocumentVersionEntity version = requireVersion(tenantId, current.versionId());
        // 读出文档，重试要把它推回处理中并重新设置目标代次。
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        // 核对任务、版本、文档三者的资源范围与代次完全一致；不一致说明数据被改坏了，绝不能盲目重试。
        requireRetryScope(current, version, document);
        // 把任务转成重新排队状态，重试次数由实体自己按上限判断。
        RagIngestJobEntity requeued = current.requeueIngest();
        // 把版本推回排队中，让它重新等待 Worker 领取。
        RagDocumentVersionEntity queuedVersion = version.retryQueued();
        // 把文档推回处理中，并重新记上这次要写入的目标代次，界面据此显示「正在解析」。
        RagDocumentEntity processingDocument = document.retryProcessing(current.generation());
        // 在一个原子操作里同时推进任务、版本、文档，并把这三者加上知识库共四个 revision 全部作为 CAS 条件。
        // 把知识库也纳入条件，是为了防止「校验完可用、写入前它刚被发起删除」这种竞态。
        repository.requeueFailedIngestJob(tenantId, requeued, current.revision(), queuedVersion,
                version.revision(), processingDocument, document.revision(), knowledgeBase.revision());
        // 回读任务返回最新状态；读不到就退回内存里那份重新排队后的对象。
        return repository.findIngestJob(tenantId, taskId).orElse(requeued);
    }

    /**
     * 恢复一个失败的删除任务。
     *
     * <p>与摄取重试的判定完全不同：删除任务的合法性看的是「文档墓碑是否还立着」。
     * 只有文档仍处于删除中时才允许继续删——已经删完或墓碑不见了，重新排队只会让 Worker 做出错误动作。</p>
     *
     * <p>数据流：任务 → 读文档 → 校验归属与墓碑状态 → 转重新排队 → revision CAS 更新 → 回读任务返回</p>
     *
     * <p>会写数据库。不需要检查知识库是否可用：删除本来就是在知识库不可用（甚至正在被删）的情况下要继续做完的。</p>
     */
    private RagIngestJobEntity retryDelete(String tenantId, RagIngestJobEntity current) {
        // 读出任务对应的文档，用来核对墓碑状态。
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        // 两个条件都必须成立：文档确实属于任务记录的那个知识库（防幂等键或数据串位），
        // 且文档仍处于删除中（墓碑还立着，说明这次删除尚未完成）。
        if (!current.knowledgeBaseId().equals(document.knowledgeBaseId())
                || document.status() != RagDocumentStatus.DELETING) {
            // 任一不符就报状态不一致，交人工核查；绝不盲目重新排队去删一个状态不明的文档。
            throw new AppException("RAG_DELETE_STATE_MISMATCH", "删除任务与文档墓碑状态不一致");
        }
        // 把删除任务转成重新排队状态。
        RagIngestJobEntity requeued = current.requeueDeletion();
        // 用当前 revision 做 CAS 更新；行数不是 1 说明期间被并发改过，直接抛冲突要求刷新重试。
        requireUpdated(repository.updateIngestJob(tenantId, requeued, current.revision()));
        // 回读任务返回最新状态；读不到就退回内存里那份。
        return repository.findIngestJob(tenantId, current.jobId()).orElse(requeued);
    }

    /**
     * 重试摄取之前，核对任务、版本、文档三者确实描述的是同一件事。
     *
     * <p>为什么必须核对：重新排队会把三张表一起改状态。如果它们其实指向不同的资源
     * （数据被外部脚本改过、迁移出错、幂等键复用），这次重试就会把无关的文档改成处理中，
     * 甚至把一个正常文档的活跃版本弄坏。宁可拒绝重试，也不能在错位的数据上写入。</p>
     *
     * <p>只读，不写库；不一致时抛范围不匹配异常。</p>
     */
    private void requireRetryScope(RagIngestJobEntity task, RagDocumentVersionEntity version,
                                   RagDocumentEntity document) {
        // 六个条件必须同时成立，任一不满足都说明三者不是描述同一件事，这次重试必须中止——
        // 前两项：任务记录的知识库要和版本、文档记录的知识库都一致（三者在同一个库里）；
        // 中间两项：任务记录的文档要和版本、文档实体记录的文档都一致（三者说的是同一份文档）；
        // 第五项：任务记录的版本编号要和读出来的版本一致（没有读错版本）；
        // 第六项：任务的代次要和版本的代次一致——代次不同意味着索引已经重建过，
        //   这个任务对应的那一代已经作废，重试它只会往一个没人查的代次里写数据。
        if (!task.knowledgeBaseId().equals(version.knowledgeBaseId())
                || !task.knowledgeBaseId().equals(document.knowledgeBaseId())
                || !task.documentId().equals(version.documentId())
                || !task.documentId().equals(document.documentId())
                || !task.versionId().equals(version.versionId())
                || task.generation() != version.generation()) {
            // 报范围不一致，交人工核查数据；不做任何自动修复，避免掩盖上游的真实问题。
            throw new AppException("RAG_INGEST_RETRY_SCOPE_MISMATCH", "任务、文档和版本范围不一致");
        }
    }

    /**
     * 对账一个已取消的任务：把还没跟上终态的文档和版本补齐。
     *
     * <p>各层职责：
     * 第一层：仍有租约就不动它，交给 Worker 收尾；
     * 第二层：读版本与文档，判断它们是否已经处于取消终态；
     * 第三层：都到位就原样返回，否则用一次原子操作把三者补齐。</p>
     *
     * <p>数据流：已取消的任务 → 有租约则返回 → 读版本与文档 → 已同步则返回
     * → 未同步则原子取消（任务 / 版本 / 文档三者 revision 作为条件）→ 回读任务返回</p>
     *
     * <p>为什么需要它：早期版本的取消只改了任务、没同步文档和版本，留下了「任务已取消但文档还在处理中」
     * 的历史数据。这类文档会永远显示正在解析。管理员再点一次取消时，这里就顺手把它修正过来。</p>
     *
     * <p>会写数据库（仅在确实需要补齐时）。</p>
     */
    private RagIngestJobEntity reconcileUnclaimedCancellation(String tenantId, RagIngestJobEntity current) {
        // 第一层：仍被 Worker 持有租约，说明它正在收尾，这里不能插手，直接返回当前状态。
        if (current.lease() != null) return current;
        // 读出版本，用来判断它有没有跟上取消终态。
        RagDocumentVersionEntity version = requireVersion(tenantId, current.versionId());
        // 读出文档，判断它的目标代次标记有没有被清掉。
        RagDocumentEntity document = requireDocument(tenantId, current.documentId());
        // 两个条件同时成立才算「已经同步到位」：版本已是已取消状态，且文档不再挂着目标代次
        // （目标代次为空表示文档上没有正在进行的写入意图）。
        if (version.status() == RagDocumentVersionStatus.CANCELLED && document.targetGeneration() == null) {
            // 已经一致，什么都不用改，原样返回。
            return current;
        }
        // 状态没跟上，用一次原子操作把任务、版本、文档三者一起补齐到取消终态；
        // 三者的 revision 同时作为 CAS 条件，避免和并发操作互相覆盖。
        repository.cancelUnclaimedIngestJob(tenantId, current, current.revision(),
                version.revision(), document.revision());
        // 回读任务返回最新状态；读不到就退回传入的那份。
        return repository.findIngestJob(tenantId, current.jobId()).orElse(current);
    }

    /**
     * 在可信租户范围内读出文档版本。
     *
     * <p>查询带租户号，跨租户读不到。读不到统一按「版本不存在或无权访问」返回，不区分两种原因，
     * 避免靠错误码差异探测别人的数据。</p>
     */
    private RagDocumentVersionEntity requireVersion(String tenantId, String versionId) {
        // 按租户查版本；查不到即抛不存在，绝不返回 null 让上层去判空。
        return repository.findDocumentVersion(tenantId, versionId)
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_VERSION_NOT_FOUND", "文档版本不存在或无权访问"));
    }

    /**
     * 在可信租户范围内读出逻辑文档。
     *
     * <p>与读版本同样的口径：带租户号查询，读不到统一按「文档不存在或无权访问」返回。</p>
     */
    private RagDocumentEntity requireDocument(String tenantId, String documentId) {
        // 按租户查文档；查不到即抛不存在，保证调用方拿到的一定是非空实体。
        return repository.findDocument(tenantId, documentId)
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "文档不存在或无权访问"));
    }

    /**
     * 读出知识库并完成「管理员 + 租户归属」校验，返回实体供后续 CAS 使用。
     *
     * <p>把「查库 + 鉴权」收拢成一个私有方法，保证所有公开入口口径一致，不会有哪个入口漏掉一步。
     * 返回实体而不是 void，是因为重试摄取时还需要用它的 revision 参与原子更新。</p>
     *
     * <p>只读，不写库。查不到或跨租户都统一按「知识库不存在或无权访问」抛错。</p>
     */
    private RagKnowledgeBaseEntity requireManageable(String tenantId, String userId, String roleCode,
                                                       String knowledgeBaseId) {
        // 按租户读知识库；租户号和知识库编号都先做非空校验，避免拿空值去查库。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        // 再做管理员 + 租户归属校验；非管理员和跨租户请求都在这里被挡住。
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        // 返回实体，调用方可以继续用它的状态和 revision 做后续判断与 CAS。
        return knowledgeBase;
    }

    /**
     * 校验一次 CAS 更新确实只影响了一行。
     *
     * <p>行数为 0 说明 revision 已经被别人改过（另一个管理员或 Worker 抢先了）；
     * 行数大于 1 说明更新条件写得过宽，属于必须立刻暴露的严重问题。两种都不允许静默通过。</p>
     */
    private void requireUpdated(int changed) {
        // 只有恰好一行被更新才算成功；0 行是并发冲突，多行说明条件不够精确。
        if (changed != 1) {
            // 统一抛并发冲突，要求调用方刷新后基于最新状态重做，绝不盲目重试覆盖。
            throw new AppException("RAG_INGEST_CONCURRENT_UPDATE", "摄取任务已变化，请刷新后重试");
        }
    }

    /**
     * 在查询之前拒绝空标识。
     *
     * <p>空值传进仓储会让查询条件变宽，可能匹配到本不该返回的数据，甚至让租户隔离失效。
     * 字段名一起传进来，报错时能直接指出缺了哪个参数。</p>
     */
    private String requireText(String value, String field) {
        // null 和空白串都算缺失，用统一的参数非法错误码带上字段名抛出。
        if (value == null || value.isBlank()) throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        // 校验通过后返回原值，便于在调用处直接内联使用。
        return value;
    }
}
