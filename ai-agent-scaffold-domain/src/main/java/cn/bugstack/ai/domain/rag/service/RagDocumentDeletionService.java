package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagDocumentDeletionRegistrationPort;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 受理「删除一份文档及其所有版本」这件不可撤销、但可以恢复重试的任务。
 *
 * <p>解决什么问题：删一份文档要清掉的东西分布在三个地方——数据库里的文档 / 版本 / 分块记录、
 * 对象存储里的原文件和解析产物、向量库里的向量。这些外部副作用不可能放进一个数据库事务里，
 * 所以采用「先立墓碑、再后台清理」的两段式：受理时把文档和它的全部版本都标记成删除中并落一条删除任务，
 * 之后由后台 Worker 照着任务逐项清理。墓碑一立就不可撤销（已经开始删向量和文件时回滚只会得到残缺文档），
 * 但任务失败后可以重新排队继续删。</p>
 *
 * <p>最关键的不变量：文档处于删除态时，必须存在对应的删除任务。否则文档永远停在「删除中」，
 * 既检索不到又清不掉，只能人工介入。所以「立墓碑」和「落任务」必须在同一个事务里原子完成。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用编排服务，只受理和登记，不执行真正的清理。</p>
 *
 * <p>谁会调用它：文档管理的 HTTP 控制器（管理员删单份文档），以及知识库级联删除协调器
 * （删整个库时逐个文档调进来）。两个入口的权限口径完全不同，所以分成两个公开方法。</p>
 *
 * <p>它向下调用什么：RAG 仓储（查知识库 / 文档 / 版本 / 任务，CAS 更新任务）、
 * 删除登记端口（原子写入文档墓碑 + 版本墓碑 + 删除任务 + Outbox 事件）、知识库授权服务。</p>
 *
 * <p>它不负责什么：不删向量、不删对象存储文件、不删分块正文（全部由后台 Worker 执行）、
 * 不提供撤销、不推进知识库代次。</p>
 */
@Service
public class RagDocumentDeletionService {

    /**
     * 删除任务默认最多执行 3 次。
     *
     * <p>写进任务账本由后台 Worker 遵守。次数用完后任务进入死信状态等人工恢复，
     * 而不是无限重试——无限重试会把一个必然失败的清理动作变成持续的资源消耗。</p>
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * RAG 主仓储：查知识库、查文档、列版本、按幂等键查任务，以及对任务做 CAS 更新。
     *
     * <p>所有查询都带租户号，跨租户既查不到也改不了。</p>
     */
    private final IRagRepository repository;
    /**
     * 删除登记端口，在一个事务里原子写入文档墓碑、全部版本墓碑、删除任务和 Outbox 事件。
     *
     * <p>四者必须同生共死。只立墓碑不落任务，文档就永远卡在删除中；只落任务不发 Outbox，
     * Worker 可能收不到通知（虽然还有数据库扫描兜底，但会明显延迟）。
     * 返回 false 表示幂等键冲突，也就是并发的另一次请求先受理成功了。</p>
     */
    private final RagDocumentDeletionRegistrationPort registrationPort;
    /**
     * 知识库授权服务，只用于对外的单文档删除入口（要求租户管理员）。
     *
     * <p>级联删除入口不走它：那是系统内部调用，权限已经在「发起知识库删除」那一步校验过了，
     * 它改用「知识库必须已处于删除中」这个更强的前置条件来自证合法。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    /**
     * 由 Spring 注入仓储、删除登记端口和授权服务；三者都是必需依赖。
     */
    public RagDocumentDeletionService(IRagRepository repository,
                                      RagDocumentDeletionRegistrationPort registrationPort,
                                      RagKnowledgeBaseAuthorizationService authorizationService) {
        // 保存仓储引用，用于读取聚合与 CAS 更新任务。
        this.repository = repository;
        // 保存删除登记端口引用，所有墓碑与任务写入都经它在一个事务内完成。
        this.registrationPort = registrationPort;
        // 保存授权服务引用，供对外删除入口做管理员校验。
        this.authorizationService = authorizationService;
    }

    /**
     * 管理员手动删除一份文档，返回删除任务。
     *
     * <p>各层职责：
     * 第一层：版本号合法性兜底（负数说明调用方没读过当前状态）；
     * 第二层：读知识库并做管理员 + 租户归属校验；
     * 第三层：读文档，并额外确认它确实挂在这个知识库下（防止用别的库的编号来删文档）；
     * 第四层：交给共用的受理逻辑完成幂等判断与墓碑登记。</p>
     *
     * <p>数据流：
     * 管理员请求（知识库编号 + 文档编号 + 预期版本号）
     * → 版本号合法性校验
     * → 读知识库 + 管理员校验
     * → 读文档 + 归属校验
     * → 共用受理逻辑（幂等短路 / 状态自检 / 版本 CAS / 立墓碑 + 落任务）
     * → 返回删除任务</p>
     *
     * <p>会写数据库（文档与版本墓碑、删除任务、Outbox），并触发后台异步清理。重复请求返回同一个任务。</p>
     *
     * <p>为什么要单独校验文档归属：仅凭文档编号查库虽然带了租户号，但同一租户下可能有多个知识库。
     * 若不比对知识库编号，管理员就能在 A 库的页面上删掉 B 库的文档，权限判断也会落在错误的库上。</p>
     */
    public RagIngestJobEntity deleteDocument(String tenantId, String userId, String roleCode,
                                              String knowledgeBaseId, String documentId,
                                              long expectedRevision) {
        // 第一层：版本号是非负递增值，负数只可能来自伪造或未初始化的请求。
        if (expectedRevision < 0) {
            // 参数非法直接中断，一次查库都不做。
            throw new AppException("RAG_DOCUMENT_REVISION_INVALID", "expectedRevision不能小于零");
        }
        // 第二层：按租户读知识库；租户号和知识库编号都先做非空校验，避免拿空值去查库。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        // 做管理员 + 租户归属校验；非管理员和跨租户请求都会被统一按「不存在」挡掉。
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        // 第三层：读文档，并用 filter 追加一道归属校验——文档必须真的挂在刚校验过的那个知识库下。
        // 不满足时和「查不到」走同一个分支，统一按文档不存在返回，不泄露它到底属于哪个库。
        RagDocumentEntity document = repository.findDocument(tenantId, requireText(documentId, "documentId"))
                .filter(value -> knowledgeBaseId.equals(value.knowledgeBaseId()))
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "文档不存在或无权访问"));

        // 第四层：走共用受理逻辑，幂等、状态自检、墓碑登记都在里面完成。
        return ensureDeletion(tenantId, knowledgeBaseId, document, expectedRevision);
    }

    /**
     * 知识库级联删除时的内部入口：删掉这个库下的某一份文档。
     *
     * <p>各层职责：
     * 第一层：读知识库；
     * 第二层：确认知识库已经立起了删除屏障（状态为删除中）——这是本入口唯一的授权依据；
     * 第三层：读文档并确认归属；
     * 第四层：交给共用受理逻辑，版本号直接用文档当前值（不需要客户端传预期版本）。</p>
     *
     * <p>数据流：租户 + 知识库编号 + 文档编号 → 读知识库 → 校验删除屏障已建立 → 读文档并校验归属
     * → 共用受理逻辑 → 返回删除任务</p>
     *
     * <p>为什么这里不做管理员校验：调用方是系统内部的级联协调器，不是用户请求。
     * 但也不能完全不设防，否则这个方法就成了绕过权限删文档的后门。所以改用一个更强的前置条件：
     * 只有当知识库自己已经处于删除中（那个状态只能由通过管理员校验的删除受理流程设置）时才允许进入。</p>
     *
     * <p>为什么版本号用文档当前值：级联删除是系统自动推进的，没有「用户看到的那一版」这个概念，
     * 乐观并发控制在这里没有意义，直接用最新值即可。</p>
     *
     * <p>会写数据库并触发后台清理。</p>
     */
    public RagIngestJobEntity ensureCascadeDeletion(String tenantId, String knowledgeBaseId,
                                                      String documentId) {
        // 第一层：按租户读知识库，租户号与知识库编号先做非空校验。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(requireText(tenantId, "tenantId"),
                        requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        // 第二层：本入口的授权全靠这一条——知识库必须已处于删除中。
        // 那个状态只能由经过管理员校验的知识库删除受理流程设置，所以它等价于「已经有人有权发起了删除」。
        if (knowledgeBase.status() != cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus.DELETING) {
            // 屏障不存在就拒绝，防止这个内部入口被当成绕过权限删文档的后门。
            throw new AppException("RAG_KB_DELETE_STATE_MISMATCH", "知识库尚未建立级联删除屏障");
        }
        // 第三层：读文档并追加归属校验，确认它确实挂在这个正在被删除的知识库下。
        RagDocumentEntity document = repository.findDocument(tenantId, requireText(documentId, "documentId"))
                .filter(value -> knowledgeBaseId.equals(value.knowledgeBaseId()))
                .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "知识库文档不存在"));
        // 第四层：走共用受理逻辑，版本号直接用文档当前值，因为级联删除不需要乐观并发控制。
        return ensureDeletion(tenantId, knowledgeBaseId, document, document.revision());
    }

    /**
     * 受理删除的共用核心：保证「文档进入删除态」和「存在删除任务」这两件事同时成立。
     *
     * <p>各层职责：
     * 第一层：用稳定幂等键查是否已有删除任务，有则走恢复或原样返回，绝不新建第二个任务；
     * 第二层：一致性自检——文档已是删除态却查不到任务，属于必须人工核查的坏状态；
     * 第三层：版本号比对，防止基于过期状态发起删除；
     * 第四层：列出全部版本，一个版本都没有说明数据不完整，无从删起；
     * 第五层：把文档和每一个版本都转成删除中状态（墓碑），并挑一个版本承载任务；
     * 第六层：原子登记「文档墓碑 + 版本墓碑 + 删除任务 + Outbox」，冲突则读并发者的任务返回。</p>
     *
     * <p>数据流：
     * 租户 + 知识库编号 + 已校验的文档 + 预期版本号
     * → 算幂等键（只绑定租户 + 文档）
     * → 查已有任务，命中则走恢复逻辑并返回
     * → 文档状态与任务存在性自检
     * → 版本号比对
     * → 列出全部版本（为空则报错）
     * → 文档转删除中 + 所有版本转删除中 + 选定承载任务的版本
     * → 生成待执行删除任务
     * → 原子登记（含 Outbox 事件）
     * → 登记冲突则读并发者任务；成功则回读任务返回</p>
     *
     * <p>为什么幂等键只绑定租户和文档编号（不含版本）：删除的语义是「这份文档整体不要了」，
     * 覆盖它的所有版本。若把版本也放进键里，同一份文档就会因为版本不同而生成多个删除任务，
     * 多个 Worker 并发清理同一份文档的不同版本，产生互相踩踏的中间状态。</p>
     *
     * <p>会写数据库并触发后台清理。</p>
     */
    private RagIngestJobEntity ensureDeletion(String tenantId, String knowledgeBaseId,
                                               RagDocumentEntity document, long expectedRevision) {

        // 取出文档编号，后面算幂等键、查版本、建任务都要用它。
        String documentId = document.documentId();
        // 算出稳定幂等键：同租户同文档永远算出同一个值，数据库对它有唯一约束，这是防重复受理的根本手段。
        String taskKey = deletionTaskKey(tenantId, documentId);
        // 第一层：先看是不是已经有删除任务了。
        RagIngestJobEntity existing = repository.findIngestJobByIdempotencyKey(tenantId, taskKey).orElse(null);
        // 已有任务就交给恢复逻辑：可能原样返回（正在跑或已完成），也可能重新排队（失败或死信）。
        // 无论哪种，都绝不新建第二个任务。
        if (existing != null) return resumeOrReturn(tenantId, document, existing);
        // 第二层：文档已经是删除态，却查不到删除任务——这违反了本类最核心的不变量。
        if (document.status() == RagDocumentStatus.DELETING || document.status() == RagDocumentStatus.DELETED) {
            // 绝不能当成新请求重新受理：文档可能已经被清了一半，重新受理会让进度和实际状态彻底错位。
            // 直接报错交给运维核查。
            throw new AppException("RAG_DELETE_TASK_MISSING", "文档处于删除态但删除任务不可见，需要运维核查");
        }
        // 第三层：比对版本号，确认发起方看到的是文档的最新状态。
        if (document.revision() != expectedRevision) {
            // 不一致要求刷新重试，避免管理员基于过期信息误删一份刚被改动过的文档。
            throw new AppException("RAG_DOCUMENT_REVISION_CONFLICT", "文档已变化，请刷新后重试");
        }

        // 第四层：列出这份文档的全部版本。删除要覆盖所有版本，所以必须先拿到完整清单。
        List<RagDocumentVersionEntity> versions = repository.listDocumentVersions(tenantId, documentId);
        // 一个版本都没有说明数据不完整（上传中断、数据被误删），此时没有可清理的对象。
        if (versions.isEmpty()) {
            // 直接报错而不是「当成删完了」：静默成功会掩盖数据不一致，让问题更晚才被发现。
            throw new AppException("RAG_DOCUMENT_VERSION_NOT_FOUND", "文档没有可删除的版本记录");
        }
        // 第五层：把文档转成删除中状态。这就是「墓碑」：文档立刻退出检索范围，但记录还在，供 Worker 按它清理。
        RagDocumentEntity deletingDocument = document.requestDeletion();
        // 流式转换：全部版本 → 每个版本各自转成删除中状态 → 收成列表。
        // 所有版本一起立墓碑，保证不会漏掉某个历史版本的向量和文件。
        List<RagDocumentVersionEntity> deletingVersions = versions.stream()
                .map(RagDocumentVersionEntity::requestDeletion).toList();
        // 任务需要挂在某一个具体版本上（任务表结构要求版本编号和代次），这里挑出承载任务的那一版。
        RagDocumentVersionEntity taskVersion = resolveTaskVersion(document, versions);
        // 生成待执行的删除任务：绑定知识库、文档和承载版本，带上幂等键（唯一约束靠它）、
        // 操作类型为删除、承载版本的代次，以及最大执行次数。
        RagIngestJobEntity task = RagIngestJobEntity.pending(tenantId, knowledgeBaseId, documentId,
                taskVersion.versionId(), id("ragtask"), taskKey, RagIngestOperation.DELETE,
                taskVersion.generation(), DEFAULT_MAX_ATTEMPTS);
        // 第六层：把文档墓碑、全部版本墓碑、删除任务和 Outbox 事件打包成一次原子登记。
        // 同一事务保证不会出现「墓碑立了但没任务」这种无法自愈的状态。
        boolean inserted = registrationPort.register(tenantId,
                new RagDocumentDeletionRegistration(deletingDocument, deletingVersions, task, id("ragevt")));
        // 登记失败只有一种原因：并发的另一次请求用同一个幂等键先受理成功了。
        if (!inserted) {
            // 把并发获胜者的任务读出来返回，对调用方而言效果与自己受理成功一样；
            // 读不到属于罕见的可见性延迟，明确提示刷新重试，绝不新建任务。
            return repository.findIngestJobByIdempotencyKey(tenantId, taskKey)
                    .orElseThrow(() -> new AppException("RAG_DELETE_CONCURRENT_RESULT_MISSING",
                            "并发删除已受理但任务暂不可见，请刷新后重试"));
        }
        // 登记成功，回读任务拿到数据库生成的完整字段；读不到就退回内存里那份，保证一定有返回值。
        return repository.findIngestJob(tenantId, task.jobId()).orElse(task);
    }

    /**
     * 已存在删除任务时决定怎么处理：原样返回，还是重新排队。
     *
     * <p>各层职责：
     * 第一层：幂等键归属自检——查出来的任务必须确实是「这份文档的删除任务」；
     * 第二层：任务已完成时，文档必须已是墓碑终态，否则两边状态矛盾；
     * 第三层：任务未完成时，文档必须仍处于删除中；
     * 第四层：任务还在正常推进（排队中、运行中等）则原样返回，不打扰它；
     * 第五层：只有失败或死信任务才重新排队，并用 revision 做 CAS 更新。</p>
     *
     * <p>数据流：
     * 已有任务 + 文档
     * → 校验任务操作类型与文档 / 知识库归属
     * → 任务已完成？→ 校验文档为已删除 → 返回
     * → 校验文档仍为删除中
     * → 任务不是失败也不是死信？→ 原样返回
     * → 转重新排队 → revision CAS 更新
     * → 回读任务返回</p>
     *
     * <p>会写数据库（仅在需要重新排队时）。任何状态矛盾都抛异常而不是自动修复，
     * 因为矛盾意味着有别的地方出了 Bug，自动「修好」只会掩盖问题。</p>
     */
    private RagIngestJobEntity resumeOrReturn(String tenantId, RagDocumentEntity document,
                                               RagIngestJobEntity existing) {
        // 第一层：三项归属自检——任务的操作类型必须是删除、文档编号必须一致、知识库编号必须一致。
        // 幂等键是摘要，理论上存在极小的碰撞可能；更现实的风险是上游算键的逻辑改动导致键复用。
        // 任一不符都说明这个任务不是这份文档的删除任务。
        if (existing.operation() != RagIngestOperation.DELETE
                || !document.documentId().equals(existing.documentId())
                || !document.knowledgeBaseId().equals(existing.knowledgeBaseId())) {
            // 幂等键指向了别的资源，绝不能拿它当本次删除的结果返回，直接报冲突。
            throw new AppException("RAG_DELETE_IDEMPOTENCY_CONFLICT", "删除任务幂等键与文档范围不一致");
        }
        // 第二层：任务已经跑完了。
        if (existing.status() == RagIngestJobStatus.COMPLETED) {
            // 那么文档必须已经是已删除终态；不是的话说明任务和文档的状态对不上。
            if (document.status() != RagDocumentStatus.DELETED) {
                // 报状态不一致而不是尝试修复：这种矛盾只可能来自 Worker 的 Bug 或数据被外部改过，需要人查。
                throw new AppException("RAG_DELETE_STATE_MISMATCH", "删除任务与文档墓碑终态不一致");
            }
            // 两边终态一致，直接把已完成的任务返回，调用方据此知道这份文档早就删完了。
            return existing;
        }
        // 第三层：任务还没完成，那文档就必须仍处于删除中。既不是删除中也不是已删除完成，说明墓碑丢了。
        if (document.status() != RagDocumentStatus.DELETING) {
            // 同样报状态不一致，交给人工核查。
            throw new AppException("RAG_DELETE_STATE_MISMATCH", "删除任务与文档墓碑状态不一致");
        }
        // 第四层：任务既没失败也没进死信，说明它还在正常推进（排队中、运行中、取消请求中等）。
        if (existing.status() != RagIngestJobStatus.FAILED && existing.status() != RagIngestJobStatus.DEAD) {
            // 原样返回不做任何干预：重复的删除请求不应该打扰一个正在正常执行的任务。
            return existing;
        }
        // 第五层：只有失败或死信任务才走到这里，把它转成重新排队状态。
        RagIngestJobEntity requeued = existing.requeueDeletion();
        // 用任务原来的 revision 做 CAS 更新，避免和 Worker 的并发写互相覆盖。
        int changed = repository.updateIngestJob(tenantId, requeued, existing.revision());
        // 受影响行数不是 1，说明期间任务被别人改过（另一个管理员点了重试，或 Worker 抢先领取了它）。
        if (changed != 1) {
            // 这不算错误，把最新状态读出来返回即可；读不到才是真异常，提示刷新重试。
            return repository.findIngestJob(tenantId, existing.jobId())
                    .orElseThrow(() -> new AppException("RAG_DELETE_CONCURRENT_RESULT_MISSING",
                            "删除任务已变化但当前不可见，请刷新后重试"));
        }
        // CAS 成功，回读最新任务返回；读不到就退回内存里那份重新排队后的对象。
        return repository.findIngestJob(tenantId, existing.jobId()).orElse(requeued);
    }

    /**
     * 挑一个版本来承载删除任务。
     *
     * <p>优先用文档当前的活跃版本：它是正在被检索的那一版，它的代次也是最新的，
     * 用它做任务的版本和代次最贴近真实情况。若文档还没有活跃版本（上传后从未处理成功），
     * 就退而用第一个历史版本，只是为了让任务有一个合法的版本编号可挂。</p>
     *
     * <p>注意任务只是「挂」在这一版上，清理范围仍然是文档的全部版本（前面已经给所有版本都立了墓碑）。</p>
     *
     * <p>只读，不写库。活跃版本编号存在但在版本列表里找不到，说明数据不一致，直接报错。</p>
     */
    private RagDocumentVersionEntity resolveTaskVersion(RagDocumentEntity document,
                                                         List<RagDocumentVersionEntity> versions) {
        // 有活跃版本就优先用它：它的代次与线上检索使用的代次一致，任务信息最准确。
        if (document.activeVersionId() != null) {
            // 在版本列表里按编号找出活跃版本；找不到说明文档记的活跃版本编号指向了一个不存在的版本，
            // 属于数据不一致，直接报错而不是随便挑一版顶上。
            return versions.stream().filter(value -> document.activeVersionId().equals(value.versionId()))
                    .findFirst().orElseThrow(() -> new AppException("RAG_DOCUMENT_ACTIVE_VERSION_MISSING",
                            "文档活动版本记录不存在"));
        }
        // 没有活跃版本（例如首次上传就失败了），用第一个历史版本承载任务；
        // 上层已经保证版本列表非空，所以这里取下标 0 是安全的。
        return versions.get(0);
    }

    /**
     * 生成删除任务的幂等键。
     *
     * <p>只绑定租户和逻辑文档，刻意不含版本号，因为删除的语义是「整份文档都不要了」。
     * 这样同一份文档无论被请求删除多少次、从哪个入口进来，都只会有一个删除任务。</p>
     */
    private String deletionTaskKey(String tenantId, String documentId) {
        // 用固定前缀区分删除任务和摄取任务的键空间，避免两类任务的幂等键互相碰撞。
        return sha256("delete\n" + tenantId + "\n" + documentId);
    }

    /**
     * 生成带业务前缀的随机标识。
     *
     * <p>前缀让日志里一眼看出是任务还是事件；随机部分用去横线的 UUID，保证外部无法猜测或枚举。</p>
     */
    private String id(String prefix) {
        // 前缀 + 下划线 + 去横线的随机 UUID；去横线是为了让编号在 URL 和日志里更紧凑。
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 计算跨进程稳定的摘要，用于生成删除任务的幂等键。
     *
     * <p>同样的输入在任何机器、任何时间都算出同样的键，所以数据库的唯一约束才能真正拦住重复受理。</p>
     */
    private String sha256(String value) {
        // SHA-256 是 JDK 必备算法，实际不会缺失，但接口签名要求处理异常，所以包一层。
        try {
            // 按 UTF-8 取字节算摘要再转十六进制；显式指定编码，保证含中文的输入在任何环境算出同一个键。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        // 走到这里说明运行环境连 SHA-256 都没有，属于 JVM 被破坏。
        } catch (NoSuchAlgorithmException e) {
            // 直接抛非法状态异常终止，绝不退化成弱哈希——幂等键一旦不可靠，重复删除就再也拦不住了。
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 在访问仓储之前拒绝空的业务标识。
     *
     * <p>字段名一起传进来，报错时能直接指出缺了哪个参数。抛业务异常而不是空指针，前端才能拿到可读提示。</p>
     */
    private String requireText(String value, String field) {
        // null 和空白串都算缺失：拿空值去查库要么查出无关数据，要么让租户隔离条件失效。
        if (value == null || value.isBlank()) {
            // 用统一的参数非法错误码，并带上具体字段名，方便快速定位。
            throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        }
        // 校验通过后返回原值，便于在调用处直接内联使用。
        return value;
    }
}
