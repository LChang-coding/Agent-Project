package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 知识库的建、查、改三个基础用例。
 *
 * <p>解决什么问题：知识库是 RAG 的容器，它的名字要在同租户内唯一（否则管理员在下拉框里根本分不清），
 * 它的索引参数（向量维度、向量集合别名）一旦创建就不能随意改动（改了会让已建好的索引全部失效），
 * 它的生命周期状态和代次也不允许通过编辑接口顺手改掉。本类就负责守住这些边界：
 * 能改的只有展示名和描述，其他字段一律原样继承。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用服务，写操作都开启事务。</p>
 *
 * <p>谁会调用它：知识库管理的 HTTP 控制器（管理员在界面新建库、看列表、改名改描述）。</p>
 *
 * <p>它向下调用什么：RAG 仓储（查列表、查单个、插入、CAS 更新）和知识库授权服务（管理员 / 成员校验）。</p>
 *
 * <p>它不负责什么：不删除知识库（删除是不可撤销的级联操作，由 RagKnowledgeBaseDeletionService 处理）、
 * 不管理文档、不管理绑定与检索策略、不建向量集合（只生成别名，真正建集合由摄取链路完成）、
 * 不推进索引代次。</p>
 */
@Service
public class RagKnowledgeBaseManagementService {

    /**
     * 新建知识库时固定使用的向量维度，768。
     *
     * <p>它必须和平台当前使用的 Embedding 模型输出维度一致。写死成常量是因为维度一旦确定就不能改：
     * 已经建好的向量都是这个长度，改维度等于让整个库的索引全部作废，只能重建。</p>
     */
    private static final int DEFAULT_EMBEDDING_DIMENSION = 768;
    /**
     * 知识库展示名的最大长度，128 个字符。
     *
     * <p>约束的是「人看的名字」，不是标识。超长名字会撑坏管理界面的列表和下拉框，也会让日志难读。</p>
     */
    private static final int MAX_NAME_LENGTH = 128;
    /**
     * 知识库描述的最大长度，512 个字符。
     *
     * <p>描述是给管理员看的说明文字，不参与检索。限长是为了防止有人把整篇文档粘进描述字段。</p>
     */
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    /**
     * RAG 仓储，负责知识库的读写。
     *
     * <p>同租户重名检查也靠它：先把本租户全部知识库列出来再比名字。所有查询都带租户号，
     * 所以重名只在本租户内判定，不同租户可以有同名知识库。</p>
     */
    private final IRagRepository repository;
    /**
     * 知识库授权服务。写操作要求管理员，读列表只要求是租户成员。
     *
     * <p>区分这两级是因为：普通成员需要在对话里选知识库，所以得能看到列表；但不能建、不能改。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorizationService;

    /**
     * 由 Spring 注入 RAG 仓储与授权服务；两者都是必需依赖。
     */
    public RagKnowledgeBaseManagementService(IRagRepository repository,
                                             RagKnowledgeBaseAuthorizationService authorizationService) {
        // 保存仓储引用，用于知识库的查询与写入。
        this.repository = repository;
        // 保存授权服务引用，每个入口先做身份和角色校验。
        this.authorizationService = authorizationService;
    }

    /**
     * 新建一个知识库。
     *
     * <p>各层职责：
     * 第一层：管理员权限校验；
     * 第二层：名称与描述归一（去空白、限长），非法直接拒绝；
     * 第三层：同租户重名检查（忽略大小写、忽略首尾空白）；
     * 第四层：生成不可猜测的知识库编号和向量集合别名，组装实体；
     * 第五层：插入数据库，受影响行数不是 1 就按冲突处理。</p>
     *
     * <p>数据流：
     * 管理员请求（名称 + 描述）
     * → 管理员校验
     * → 名称 / 描述归一与限长
     * → 列出本租户全部知识库做重名比对
     * → 生成知识库编号 + 向量集合别名
     * → 组装实体（可见性=租户共享，状态=启用，代次=0，revision=0）
     * → 插入数据库
     * → 返回新建实体</p>
     *
     * <p>为什么代次初始为 0：代次表示「第几代索引可用」，此刻库里一份文档都没有，自然还没有任何一代索引。
     * 第一份文档摄取成功后，代次才会被推进到 1。所以刚建好的库检索出来必然是空的，这是正常的。</p>
     *
     * <p>会写数据库并开启事务。主要失败条件：非管理员、名称为空或超长、描述超长、同租户重名、插入冲突。</p>
     *
     * <p>为什么重名要忽略大小写和空白：「资料库」和「资料库 」在管理员眼里是同一个名字，
     * 允许它们共存只会造成误选。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagKnowledgeBaseEntity create(String tenantId, String userId, String roleCode,
                                         String name, String description) {
        // 第一层：只有本租户的 owner 或 admin 能建库。
        authorizationService.requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：名称去掉首尾空白并限长；空名会让界面出现一行看不见的记录，所以直接拒绝。
        String normalizedName = normalizeName(name);
        // 描述同样归一；空描述会被归一成 null，数据库里存 NULL 而不是空串，查询判断更简单。
        String normalizedDescription = normalizeDescription(description);
        // 第三层：流式重名检查——列出本租户全部知识库 → 只取名字 → 逐个与新名字比对（忽略大小写、去首尾空白）。
        // 查询带租户号，所以判定范围严格限制在本租户内，不同租户之间不算重名。
        boolean duplicate = repository.listKnowledgeBases(tenantId).stream()
                .map(RagKnowledgeBaseEntity::name)
                .anyMatch(existingName -> normalizedName.equalsIgnoreCase(existingName.trim()));
        // 重名直接拒绝并给出可读提示，让管理员换个名字，而不是建出两个分不清的库。
        if (duplicate) throw conflict();

        // 第四层：生成带 kb_ 前缀的随机编号。去掉 UUID 的横线让编号更紧凑，随机性保证外界无法枚举知识库。
        String knowledgeBaseId = "kb_" + UUID.randomUUID().toString().replace("-", "");
        // 组装知识库实体：拥有者记为创建人；可见性默认租户共享（建库通常是给全租户用的，需要私有可后续调整）；
        // 状态直接置为启用；检索策略先留空表示用平台默认；向量维度用固定常量；
        // 向量集合别名带上租户摘要做隔离；当前代次和 revision 都从 0 开始。
        RagKnowledgeBaseEntity knowledgeBase = new RagKnowledgeBaseEntity(
                tenantId, userId, knowledgeBaseId, normalizedName, normalizedDescription,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null,
                DEFAULT_EMBEDDING_DIMENSION, collectionAlias(tenantId, knowledgeBaseId), 0L, 0L);
        // 第五层：插入数据库。受影响行数不是 1，说明并发的另一个请求刚好插入了同名库（唯一约束冲突），
        // 按同名冲突返回，让管理员换名重试。
        if (repository.insertKnowledgeBase(tenantId, knowledgeBase) != 1) throw conflict();
        // 插入成功，把内存里这份实体返回，前端可以立即渲染新建的库。
        return knowledgeBase;
    }

    /**
     * 列出当前租户下的全部知识库。
     *
     * <p>只要求是租户成员而不是管理员，因为普通用户在对话设置里需要挑选知识库。
     * 隔离完全靠仓储查询里的租户号实现，不同租户永远看不到彼此的库。</p>
     *
     * <p>只读，不写库。注意这里不按可见性过滤——私有库也会出现在列表里；
     * 真正的可见性过滤发生在会话 RAG 设置和检索链路里。</p>
     */
    public List<RagKnowledgeBaseEntity> list(String tenantId, String userId) {
        // 只需确认身份可信且属于本租户；普通成员也要能看到列表才能在对话里选知识库。
        authorizationService.requireTenantMember(tenantId, userId);
        // 按租户查全部知识库；租户隔离由仓储的查询条件保证。
        return repository.listKnowledgeBases(tenantId);
    }

    /**
     * 修改知识库的展示名和描述，其余字段一律不动。
     *
     * <p>各层职责：
     * 第一层：管理员权限校验（先按角色粗筛）；
     * 第二层：读出实体，再做一次「管理员 + 这个库确实归本租户」的精确校验；
     * 第三层：生命周期校验，删除中和已删除的库不允许再编辑；
     * 第四层：版本号比对，防止基于过期页面覆盖别人的修改；
     * 第五层：名称与描述归一，并做「排除自己」的同租户重名检查；
     * 第六层：组装新实体（只换名字和描述，revision 加一），用 CAS 更新落库。</p>
     *
     * <p>数据流：
     * 管理员请求（知识库编号 + 预期版本号 + 新名称 + 新描述）
     * → 管理员校验
     * → 按租户读实体（读不到按不存在）
     * → 管理员 + 租户归属精确校验
     * → 生命周期状态校验
     * → 版本号比对
     * → 名称 / 描述归一
     * → 排除自身的重名检查
     * → 组装新实体（其余字段原样继承，revision + 1）
     * → revision CAS 更新
     * → 返回更新后的实体</p>
     *
     * <p>为什么其余字段必须原样继承：拥有者、可见性、状态、检索策略、向量维度、集合别名、索引代次
     * 各有专门的流程去改。若在这里顺手改掉，就可能出现「代次被回退」「集合别名变了但向量还在老集合」这类灾难。</p>
     *
     * <p>会写数据库并开启事务。主要失败条件：非管理员、库不存在或跨租户、库处于删除态、
     * 版本号不匹配、改名后与别的库重名、CAS 更新时被并发抢先。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagKnowledgeBaseEntity update(String tenantId, String userId, String roleCode,
                                         String knowledgeBaseId, long expectedRevision,
                                         String name, String description) {
        // 第一层：先按角色粗筛，非管理员直接挡在门外，一次查库都不做。
        authorizationService.requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：按租户读出实体。requireId 会把空值和超长编号统一当成「不存在」，不暴露标识校验细节。
        RagKnowledgeBaseEntity existing = repository.findKnowledgeBase(tenantId, requireId(knowledgeBaseId))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        // 再做一次精确校验：确认这个库确实归当前租户管；跨租户请求在这里被统一按「不存在」挡掉。
        authorizationService.requireManageable(tenantId, userId, roleCode, existing);
        // 第三层：删除中和已删除的库不允许编辑。改一个正在被清理的库毫无意义，
        // 还会让删除任务的进度和账本对不上。
        if (existing.status() == RagKnowledgeBaseStatus.DELETING
                || existing.status() == RagKnowledgeBaseStatus.DELETED) {
            // 处于删除态直接拒绝，提示管理员这个库已经不可用。
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "删除中的知识库不能编辑");
        }
        // 第四层：比对版本号。管理员是基于某一版页面提交的修改，版本号不一致说明期间别人改过。
        if (expectedRevision != existing.revision()) {
            // 要求刷新后重试，避免把别人刚保存的修改直接覆盖掉。
            throw revisionConflict();
        }
        // 第五层：新名称归一并限长，规则与创建时完全一致。
        String normalizedName = normalizeName(name);
        // 新描述同样归一，空描述归一成 null。
        String normalizedDescription = normalizeDescription(description);
        // 排除自身的重名检查：列出本租户全部知识库 → 先滤掉自己（否则名字没改也会被判重名）
        // → 只取名字 → 逐个忽略大小写比对。
        boolean duplicate = repository.listKnowledgeBases(tenantId).stream()
                .filter(item -> !existing.knowledgeBaseId().equals(item.knowledgeBaseId()))
                .map(RagKnowledgeBaseEntity::name)
                .anyMatch(value -> normalizedName.equalsIgnoreCase(value.trim()));
        // 与别的库重名就拒绝，保证同租户内名字始终唯一可辨。
        if (duplicate) throw conflict();
        // 第六层：组装更新后的实体。除名称和描述外，拥有者、可见性、状态、检索策略、向量维度、
        // 集合别名、当前代次全部原样继承，revision 加一表示这是一次新的修改。
        RagKnowledgeBaseEntity updated = new RagKnowledgeBaseEntity(existing.tenantId(), existing.ownerUserId(),
                existing.knowledgeBaseId(), normalizedName, normalizedDescription, existing.visibility(),
                existing.status(), existing.retrievalProfileId(), existing.embeddingDimension(),
                existing.collectionAlias(), existing.currentGeneration(), existing.revision() + 1);
        // 用原来的 revision 作为 CAS 条件更新；受影响行数不是 1 说明期间有并发修改抢先落库。
        if (repository.updateKnowledgeBase(tenantId, updated, expectedRevision) != 1) {
            // 并发冲突时中止并要求刷新重试，绝不重试覆盖，否则会静默丢掉别人的修改。
            throw revisionConflict();
        }
        // 更新成功，返回新实体，前端据此刷新界面（包含新的 revision，供下一次编辑做 CAS）。
        return updated;
    }

    /**
     * 归一知识库展示名：去掉首尾空白并限长。
     *
     * <p>空名和超长名都直接抛业务异常，让前端给出明确提示，而不是存进库里再造成界面异常。</p>
     */
    private String normalizeName(String value) {
        // null 和纯空白都算没填：一个看不见名字的知识库在界面上完全无法选择和识别。
        if (value == null || value.isBlank()) {
            // 明确报「名称不能为空」，前端可直接展示这句提示。
            throw new AppException("RAG_KNOWLEDGE_BASE_NAME_INVALID", "知识库名称不能为空");
        }
        // 去掉首尾空白后再用，避免「资料库」和「资料库 」被当成两个不同的名字。
        String normalized = value.trim();
        // 归一之后再判长度：先 trim 再限长，用户末尾多打的空格不会白白占用长度配额。
        if (normalized.length() > MAX_NAME_LENGTH) {
            // 超长直接拒绝，避免撑坏管理界面的列表和下拉框。
            throw new AppException("RAG_KNOWLEDGE_BASE_NAME_INVALID", "知识库名称不能超过128个字符");
        }
        // 返回归一后的名字，后续重名比对和落库都用它。
        return normalized;
    }

    /**
     * 校验并清理外部传入的知识库编号。
     *
     * <p>刻意把「格式不对」也报成「不存在」：如果格式错误单独返回一种错误，攻击者就能靠错误码差异
     * 摸清编号的格式规则，进而更高效地枚举。长度上限 64 同时挡住了超长入参打爆查询的情况。</p>
     */
    private String requireId(String value) {
        // 空值、空白、超长都视为非法编号；超长限制顺带防止有人用巨长字符串去压数据库。
        if (value == null || value.isBlank() || value.length() > 64) {
            // 统一报「知识库不存在」，不透露到底是格式问题还是真的没有这条记录。
            throw new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在");
        }
        // 去掉首尾空白后返回；前端输入框很容易带上空格，不清理会导致明明存在却查不到。
        return value.trim();
    }

    /**
     * 归一知识库描述：空描述落成 null，其余去空白并限长。
     *
     * <p>空描述统一存 NULL 而不是空串，这样查询和展示逻辑只需要判一次 null，不必同时兼容两种「空」。</p>
     */
    private String normalizeDescription(String value) {
        // 没填描述是完全正常的，直接返回 null 让数据库存 NULL。
        if (value == null || value.isBlank()) return null;
        // 去掉首尾空白，避免存进一串只有空格的描述。
        String normalized = value.trim();
        // 同样先 trim 再限长，用户多打的空格不占配额。
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            // 超长直接拒绝：描述是给人看的说明，不该被当成正文粘贴区。
            throw new AppException("RAG_KNOWLEDGE_BASE_DESCRIPTION_INVALID", "知识库描述不能超过512个字符");
        }
        // 返回归一后的描述，交给调用方落库。
        return normalized;
    }

    /**
     * 生成向量集合别名：租户摘要 + 知识库编号。
     *
     * <p>各层职责：算租户号的 SHA-256 摘要 → 取前 8 字节转十六进制 → 拼成 rag_租户摘要_知识库编号。</p>
     *
     * <p>为什么要用摘要而不是租户号原文：租户号常常带有可识别的业务含义（公司简称、编号规律），
     * 直接暴露在向量库的集合名里，运维界面和监控指标上就能看出平台有哪些客户。用摘要既做到隔离，
     * 又不泄露租户身份。</p>
     *
     * <p>为什么后面还要拼知识库编号：光有租户摘要无法区分同一租户的多个库；带上编号后，
     * 从集合名就能直接反查到具体是哪个知识库，排查问题时不用再翻映射表。</p>
     *
     * <p>纯计算，不写库、不真正创建向量集合（创建由摄取链路完成）。</p>
     */
    private String collectionAlias(String tenantId, String knowledgeBaseId) {
        // SHA-256 是 JDK 必备算法，实际不会缺失，但接口签名要求处理异常，所以包一层。
        try {
            // 按 UTF-8 取租户号字节算摘要；显式指定编码，保证同一个租户号在任何环境算出同一个别名。
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(StandardCharsets.UTF_8));
            // 只取摘要的前 8 个字节转成十六进制（16 个字符）：足够避免碰撞，又不会让集合名长到难用。
            return "rag_" + HexFormat.of().formatHex(digest, 0, 8) + "_" + knowledgeBaseId;
        // 走到这里说明运行环境连 SHA-256 都没有，属于 JVM 被破坏。
        } catch (NoSuchAlgorithmException e) {
            // 直接抛非法状态异常终止，绝不退化成明文租户号——那会把租户身份泄露到向量库的集合名里。
            throw new IllegalStateException("JVM缺少SHA-256摘要算法", e);
        }
    }

    /**
     * 生成统一的「同名冲突」异常。
     *
     * <p>两条路径共用：主动重名检查命中，以及并发插入触发唯一约束。对管理员而言原因相同，
     * 提示也应该一样——换个名字重试。</p>
     */
    private AppException conflict() {
        // 只构造异常返回，由各处自行抛出，保证两条冲突路径对外表现完全一致。
        return new AppException("RAG_KNOWLEDGE_BASE_CONFLICT", "当前租户已存在同名知识库，请更换名称后重试");
    }

    /**
     * 生成统一的「版本冲突」异常。
     *
     * <p>前置版本号比对失败和 CAS 更新失败共用它。两者含义相同：数据已被别人改过，
     * 必须刷新后基于最新状态重做，不能盲目重试覆盖。</p>
     */
    private AppException revisionConflict() {
        // 只构造异常返回，让前置检查和 CAS 失败两条路径给出完全一致的提示。
        return new AppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", "知识库已被其他操作更新，请刷新后重试");
    }
}
