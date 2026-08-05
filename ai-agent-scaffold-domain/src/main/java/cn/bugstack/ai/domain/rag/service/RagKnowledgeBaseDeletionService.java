package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 受理、查询和恢复「删除整个知识库」这件不可撤销的重活。
 *
 * <p>解决什么问题：删一个知识库不是删一行记录，而是要连带清掉它下面所有文档、所有版本、所有分块，
 * 以及对象存储里的原文件和向量库里的向量。这些副作用分布在多个外部系统，不可能在一个数据库事务里做完。
 * 所以这里采用「先立屏障、再后台逐个清理」的两段式：受理时把知识库状态改成删除中并落一条任务账本，
 * 之后由后台任务照着账本一步步清。屏障一立就不可撤销，因为一旦开始删向量和文件，回滚出来的库已经是残缺的了。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用编排服务。它只负责受理和账本状态流转，不执行真正的清理动作。</p>
 *
 * <p>谁会调用它：知识库管理的 HTTP 控制器——管理员点删除、刷新页面看进度、点重试。</p>
 *
 * <p>它向下调用什么：RAG 仓储（查知识库、数文档）、删除任务仓储（原子登记屏障与账本、CAS 更新任务）、
 * 知识库授权服务（每个入口都要求租户管理员）。</p>
 *
 * <p>它不负责什么：不删向量、不删对象存储文件、不删分块记录（这些都由后台删除 Worker 按账本执行）、
 * 不提供取消能力（屏障不可撤销）、不做租户身份解析。</p>
 */
@Service
public class RagKnowledgeBaseDeletionService {
    /**
     * 删除任务允许被恢复（重新排队）的最大次数，5 次。
     *
     * <p>比单文档删除给得更宽：知识库级删除要跨很多文档和外部系统，中途遇到网络抖动、
     * 向量库超时的概率高得多，多给几次机会能避免管理员反复手工介入。
     * 次数用完后任务会停在失败态，需要人工处理。</p>
     */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * RAG 主仓储，这里只用来读：查知识库实体、统计待清理的文档数量。
     *
     * <p>所有查询都带租户号，跨租户查不到任何东西。</p>
     */
    private final IRagRepository repository;
    /**
     * 删除任务仓储，负责账本的原子登记与 CAS 更新。
     *
     * <p>register 会在同一个事务里做两件事：把知识库状态改成删除中（带 revision CAS），并插入任务账本。
     * 两件事必须同时成功，否则会出现「状态是删除中但没有账本」或「有账本但库还在正常服务」这两种都无法自愈的脏状态。</p>
     */
    private final RagKnowledgeBaseDeletionRepository deletionRepository;
    /**
     * 知识库授权服务，本类每一个公开入口都先过它。
     *
     * <p>连「查询删除进度」都要求管理员权限，因为进度里带着文档数量等内部信息。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    /**
     * 由 Spring 注入 RAG 仓储、删除任务仓储与授权服务；三者都是必需依赖。
     */
    public RagKnowledgeBaseDeletionService(IRagRepository repository,
                                            RagKnowledgeBaseDeletionRepository deletionRepository,
                                            RagKnowledgeBaseAuthorizationService authorizationService) {
        // 保存主仓储引用，用于读知识库和数文档。
        this.repository = repository;
        // 保存删除任务仓储引用，用于原子登记屏障和更新账本。
        this.deletionRepository = deletionRepository;
        // 保存授权服务引用，每个入口都要先做管理员校验。
        this.authorizationService = authorizationService;
    }

    /**
     * 受理一次不可撤销的知识库级联删除，返回删除任务账本。
     *
     * <p>各层职责：
     * 第一层：参数兜底，预期版本号不能是负数（负数说明调用方压根没读过当前状态）；
     * 第二层：读知识库并做管理员 + 租户归属校验；
     * 第三层：幂等短路，已经有账本就直接返回它，重复点删除不会产生第二个任务；
     * 第四层：一致性自检，状态已是删除态却查不到账本，说明数据被外部改坏了，此时必须报错而不是重新受理；
     * 第五层：版本号 CAS 前置检查，防止基于过期页面发起删除；
     * 第六层：统计待清理文档数并生成任务账本（含幂等键与最大恢复次数）；
     * 第七层：在一个事务里原子登记「立屏障 + 插账本」，登记失败说明并发已被别人受理，回头把别人的账本读出来返回。</p>
     *
     * <p>数据流：
     * 管理员删除请求（知识库编号 + 预期版本号）
     * → 版本号合法性校验
     * → 读知识库 + 管理员与租户校验
     * → 查已有账本，有则直接返回（幂等）
     * → 状态与账本一致性自检
     * → 版本号比对（不一致则要求刷新重试）
     * → 统计文档数 → 生成幂等键 → 生成任务账本
     * → 原子登记：知识库转删除中（revision CAS） + 插入账本
     * → 登记成功则回读账本返回；登记失败则读并发者的账本返回</p>
     *
     * <p>关键输入：expectedRevision 是管理员在页面上看到的那一版知识库版本号，用于乐观并发控制。</p>
     *
     * <p>会写数据库：改知识库状态、插入删除任务账本，两者在同一事务内。不直接删除任何数据，也不删外部文件与向量。</p>
     *
     * <p>为什么幂等这么重要：删除是不可撤销的重操作，管理员手抖点两次、前端重试、网络重发都很常见。
     * 如果每次都新建任务，就会有多个 Worker 同时清理同一个库，产生互相踩踏的中间状态。</p>
     */
    public RagKnowledgeBaseDeleteTaskEntity requestDeletion(String tenantId, String userId,
                                                              String roleCode, String knowledgeBaseId,
                                                              long expectedRevision) {
        // 第一层：版本号是无符号递增的，负数只可能来自伪造或未初始化的请求，直接拒绝。
        if (expectedRevision < 0) {
            // 参数非法直接中断，一次仓储查询都不发起。
            throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_INVALID", "expectedRevision不能小于零");
        }
        // 第二层：读出知识库并完成管理员 + 租户归属校验；查不到或不是本租户的都会在里面统一按「不存在」抛错。
        RagKnowledgeBaseEntity knowledgeBase = requireManageable(
                tenantId, userId, roleCode, knowledgeBaseId);
        // 第三层：先看这个库是不是已经有删除任务账本。
        RagKnowledgeBaseDeleteTaskEntity existing = deletionRepository.findByKnowledgeBaseId(
                tenantId, knowledgeBaseId).orElse(null);
        // 已有账本说明之前已经受理过，直接把同一个任务原样返回，实现天然幂等；
        // 绝不新建第二个任务，否则会有两个 Worker 同时清理同一个库。
        if (existing != null) return existing;
        // 第四层：一致性自检。状态是删除中或已删除，却查不到账本，说明账本被外部误删或数据被直接改过。
        if (knowledgeBase.status() == RagKnowledgeBaseStatus.DELETING
                || knowledgeBase.status() == RagKnowledgeBaseStatus.DELETED) {
            // 这种情况绝不能当成「新的删除请求」重新受理：库可能已经清了一半，重新受理会把统计和进度全搞乱。
            // 直接报错交给人工排查。
            throw new AppException("RAG_KB_DELETE_TASK_MISSING", "知识库处于删除态但任务账本不存在");
        }
        // 第五层：比对版本号。管理员是基于某一版页面点的删除，版本号不一致说明期间库被别人改过（改名、停用等）。
        if (knowledgeBase.revision() != expectedRevision) {
            // 要求刷新后重试，避免管理员在看到旧信息的情况下误删了一个已经被改动过的库。
            throw new AppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", "知识库已变化，请刷新后重试");
        }
        // 第六层：统计当前有多少文档要清理。这个数字写进账本作为进度分母，前端据此显示「已清理 x / y」。
        int documentCount = repository.listDocuments(tenantId, knowledgeBaseId).size();
        // 生成待执行的任务账本：记录租户、知识库、发起人、任务编号，
        // 幂等键由「固定前缀 + 租户 + 知识库」摘要而成（保证同一个库只可能有一个删除任务），
        // 并带上待清理文档数与最大恢复次数。
        RagKnowledgeBaseDeleteTaskEntity task = RagKnowledgeBaseDeleteTaskEntity.pending(
                tenantId, knowledgeBaseId, requireText(userId, "userId"), id("ragkbdel"),
                sha256("kb-delete\n" + tenantId + "\n" + knowledgeBaseId),
                documentCount, MAX_ATTEMPTS);
        // 第七层：把「知识库转删除中」和「插入任务账本」打包成一次原子登记。
        // requestDeletion() 生成的是立好屏障的新状态；expectedRevision 作为 CAS 条件，
        // 保证并发场景下只有一个请求能立起屏障。
        boolean inserted = deletionRepository.register(tenantId,
                new RagKnowledgeBaseDeleteRegistration(
                        knowledgeBase.requestDeletion(), expectedRevision, task));
        // 登记失败只有一种原因：并发的另一个请求先立起了屏障（CAS 或幂等键冲突）。
        if (!inserted) {
            // 这不算错误，把并发者创建的账本读出来返回即可，对调用方而言效果和自己受理成功一样。
            // 读不到才是真异常（事务已提交但从库还没可见），此时明确提示稍后再查，不要新建任务。
            return deletionRepository.findByKnowledgeBaseId(tenantId, knowledgeBaseId)
                    .orElseThrow(() -> new AppException("RAG_KB_DELETE_CONCURRENT_RESULT_MISSING",
                            "并发删除已受理但任务暂不可见"));
        }
        // 登记成功，回读一次账本拿到数据库生成的完整字段（例如时间戳、revision）；
        // 读不到就退回用内存里那份，保证接口一定有返回值而不是报错。
        return deletionRepository.findByTaskId(tenantId, task.taskId()).orElse(task);
    }

    /**
     * 按任务编号查删除进度，查之前先反查知识库并复核管理员权限。
     *
     * <p>各层职责：
     * 第一层：校验租户号与任务编号非空，然后按租户查账本；
     * 第二层：用账本里记的知识库编号反查知识库，并做管理员 + 归属校验。</p>
     *
     * <p>数据流：租户 + 任务编号 → 查账本 → 取出知识库编号 → 读知识库并校验管理员权限 → 返回账本</p>
     *
     * <p>为什么要「反查知识库再鉴权」：任务编号是随机串，但不能只靠它猜不到就当安全。
     * 权限必须挂在真实资源上，所以要顺着账本找回知识库，再确认这个人确实能管它。</p>
     *
     * <p>只读，不写库。查不到账本抛任务不存在。</p>
     */
    public RagKnowledgeBaseDeleteTaskEntity requireTask(String tenantId, String userId,
                                                          String roleCode, String taskId) {
        // 第一层：按租户查账本，租户号和任务编号都先做非空校验，避免用空值去查库。
        RagKnowledgeBaseDeleteTaskEntity task = deletionRepository.findByTaskId(
                        requireText(tenantId, "tenantId"), requireText(taskId, "taskId"))
                .orElseThrow(() -> new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在"));
        // 第二层：拿账本里记的知识库编号回查知识库并做管理员校验；
        // 越权者即使拿到了任务编号，也会在这一步被按「知识库不存在」挡掉。
        requireManageable(tenantId, userId, roleCode, task.knowledgeBaseId());
        // 权限确认后才把账本返回，前端据此渲染删除进度。
        return task;
    }

    /**
     * 按知识库编号查它的删除任务，供页面刷新后重新挂上进度轮询。
     *
     * <p>场景：管理员点了删除，页面还在轮询进度时刷新了浏览器，此时前端手里只有知识库编号、没有任务编号，
     * 就用这个入口把任务找回来继续轮询。</p>
     *
     * <p>数据流：租户 + 知识库编号 → 读知识库并校验管理员权限 → 按知识库查账本 → 返回账本</p>
     *
     * <p>只读，不写库。库存在但没有删除任务时抛任务不存在，前端据此判断这个库并没在删除中。</p>
     */
    public RagKnowledgeBaseDeleteTaskEntity requireTaskByKnowledgeBase(String tenantId, String userId,
                                                                        String roleCode, String knowledgeBaseId) {
        // 先读知识库并做管理员 + 归属校验，权限校验永远排在数据读取之前。
        RagKnowledgeBaseEntity knowledgeBase = requireManageable(
                tenantId, userId, roleCode, knowledgeBaseId);
        // 用校验通过的知识库编号（而不是入参原值）查账本，确保查的一定是刚刚鉴权通过的那个库；
        // 查不到说明这个库没在删除中，按任务不存在返回。
        return deletionRepository.findByKnowledgeBaseId(tenantId, knowledgeBase.knowledgeBaseId())
                .orElseThrow(() -> new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在"));
    }

    /**
     * 让一个失败的删除任务重新排队，继续往下清理。
     *
     * <p>各层职责：
     * 第一层：复用按任务编号查询的入口，一次完成账本读取与管理员鉴权；
     * 第二层：回读知识库，确认屏障还在（状态仍是删除中）；
     * 第三层：把账本改成重新排队状态，用账本自身的 revision 做 CAS 更新；
     * 第四层：回读最新账本返回。</p>
     *
     * <p>数据流：
     * 租户 + 用户 + 角色 + 任务编号
     * → 查账本并鉴权
     * → 读知识库，校验状态仍为删除中
     * → 账本转重新排队
     * → CAS 更新账本（失败说明并发已改动）
     * → 回读账本返回</p>
     *
     * <p>为什么必须确认状态仍是删除中：如果库已经删完（已删除）或屏障因异常被清掉，
     * 重新排队会让 Worker 去清一个已经不存在或已经恢复正常的库，产生无意义甚至危险的操作。</p>
     *
     * <p>会写数据库：更新删除任务账本状态。不直接执行清理，实际清理仍由后台 Worker 照账本进行。</p>
     */
    public RagKnowledgeBaseDeleteTaskEntity retry(String tenantId, String userId,
                                                    String roleCode, String taskId) {
        // 第一层：复用查询入口，一次完成「账本存在 + 管理员有权限」两项校验。
        RagKnowledgeBaseDeleteTaskEntity current = requireTask(
                tenantId, userId, roleCode, taskId);
        // 第二层：回读知识库，用来确认删除屏障当前还立着。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(tenantId,
                        current.knowledgeBaseId()).orElseThrow(() ->
                // 库都查不到了（可能已被彻底清理并移除记录），没有重试的对象，直接报不存在。
                new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        // 只有仍处于「删除中」才允许恢复：已删除说明活已经干完，其他状态说明屏障不在，
        // 这两种情况下重新排队都会让 Worker 做出错误动作。
        if (knowledgeBase.status() != RagKnowledgeBaseStatus.DELETING) {
            // 状态不符按状态不匹配返回，让管理员先刷新看清当前进度。
            throw new AppException("RAG_KB_DELETE_STATE_MISMATCH", "知识库不在可恢复的删除状态");
        }
        // 第三层：把账本改成重新排队状态；恢复次数是否已用完由账本实体自己判断。
        RagKnowledgeBaseDeleteTaskEntity requeued = current.requeue();
        // 用账本原来的 revision 做 CAS 更新：受影响行数不是 1，说明期间有别人（另一个管理员或 Worker）改过账本。
        if (deletionRepository.update(tenantId, requeued, current.revision()) != 1) {
            // 并发冲突时中止，避免把 Worker 刚写入的进度覆盖回旧值。
            throw new AppException("RAG_KB_DELETE_CONCURRENT_UPDATE", "知识库删除任务已变化");
        }
        // 第四层：回读最新账本返回，让前端立刻看到重新排队后的状态；读不到则退回内存里那份。
        return deletionRepository.findByTaskId(tenantId, taskId).orElse(requeued);
    }

    /**
     * 在可信租户范围内读出知识库，并完成管理员授权。
     *
     * <p>把「查库 + 鉴权」收拢成一个私有方法，保证四个公开入口的口径完全一致，不会有哪个入口漏掉一步。</p>
     *
     * <p>只读，不写库。查不到或不属于当前租户，都统一按「不存在或无权访问」抛错，避免暴露资源存在性。</p>
     */
    private RagKnowledgeBaseEntity requireManageable(String tenantId, String userId,
                                                       String roleCode, String knowledgeBaseId) {
        // 按租户查知识库，租户号和知识库编号先做非空校验，避免拿空值去查库。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(
                        requireText(tenantId, "tenantId"), requireText(knowledgeBaseId, "knowledgeBaseId"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问"));
        // 再做管理员 + 租户归属校验；非管理员和跨租户请求都在这里被挡住。
        authorizationService.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        // 两步都通过后返回实体，调用方可以放心基于它做后续判断。
        return knowledgeBase;
    }

    /**
     * 生成带业务前缀的随机任务编号。
     *
     * <p>前缀便于日志里一眼看出这是什么对象；随机部分用去掉横线的 UUID，保证外部无法猜测或枚举任务编号。</p>
     */
    private String id(String prefix) {
        // 前缀 + 下划线 + 去横线的随机 UUID；去掉横线是为了让编号在 URL 和日志里更紧凑。
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成删除操作的幂等键摘要。
     *
     * <p>输入是「固定前缀 + 租户 + 知识库」，所以同一个租户的同一个知识库永远算出同一个键。
     * 数据库对这个键建唯一约束，于是并发重复受理最多只能有一个成功，另一个会走并发分支去读已有账本。</p>
     */
    private String sha256(String value) {
        // SHA-256 是 JDK 必备算法，正常永远不会缺失，但接口签名要求处理异常，所以包一层。
        try {
            // 按 UTF-8 取字节算摘要再转成十六进制字符串；指定编码是为了让含中文的输入在任何环境下算出同一个键。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        // 走到这里说明 JVM 连 SHA-256 都没有，属于运行环境被破坏，不是业务能处理的情况。
        } catch (NoSuchAlgorithmException e) {
            // 直接抛非法状态异常终止，而不是退化成弱哈希——幂等键一旦不可靠，重复删除就拦不住了。
            throw new IllegalStateException("JVM不支持SHA-256", e);
        }
    }

    /**
     * 在访问仓储之前拒绝空的业务标识。
     *
     * <p>字段名一起传进来，报错信息能直接指出是哪个参数缺失，省掉一轮排查。
     * 抛业务异常而不是空指针，前端才能拿到可读提示。</p>
     */
    private String requireText(String value, String field) {
        // null 和空白串都算缺失：拿空值去查库要么查出一堆无关数据，要么让租户隔离失效。
        if (value == null || value.isBlank()) {
            // 用统一的参数非法错误码，并带上具体字段名，方便定位。
            throw new AppException("RAG_PARAM_INVALID", field + "不能为空");
        }
        // 校验通过后把原值返回，便于在调用处直接内联使用。
        return value;
    }
}
