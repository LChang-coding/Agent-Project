package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 管理员维护「检索怎么查」和「谁能查哪个知识库」这两类配置。
 *
 * <p>解决什么问题：检索效果由一堆参数决定（走稠密还是混合、各阶段召回多少条、要不要重排、
 * 相似度阈值多少、上下文预算多大）。这些参数抽成「检索策略」（profile）可以复用；
 * 而「哪个 Agent 或工作流能读哪个知识库、用哪份策略、占多少 Token」则抽成「绑定」（binding）。
 * 本类就是这两张配置表的维护入口，核心职责是在写库之前把所有跨对象的一致性校验做完。</p>
 *
 * <p>属于哪一层：领域层（domain）的应用服务，所有写操作都开事务。</p>
 *
 * <p>谁会调用它：检索配置管理的 HTTP 控制器（管理员在界面配策略、配绑定）。</p>
 *
 * <p>它向下调用什么：RAG 仓储（策略与绑定的增删改查、查知识库）、知识库授权服务（要求租户管理员）、
 * 绑定目标授权服务（确认 Agent 或工作流真实、属于本租户、当前可运行）。</p>
 *
 * <p>它不负责什么：不执行检索、不校验参数之间的数值合理性（那些规则写在策略实体的构造校验里）、
 * 不改知识库本身、不做运行时的可见性判断。</p>
 *
 * <p>为什么写操作都要 CAS：检索参数直接影响线上问答质量，多个管理员同时改同一份策略时，
 * 后保存的不能静默覆盖先保存的，否则会出现「我明明调过参数，怎么又变回去了」的问题。</p>
 */
@Service
public class RagRetrievalConfigurationService {

    /**
     * 检索策略名称的最大长度，128 个字符。
     *
     * <p>名称是给管理员在下拉框里辨认策略用的，限长防止界面被撑坏。</p>
     */
    private static final int MAX_NAME_LENGTH = 128;
    /**
     * 绑定目标（Agent 或工作流）编号的最大长度，64 个字符。
     *
     * <p>目标编号来自外部输入，限长既防止界面异常，也避免有人用超长字符串去压数据库查询。</p>
     */
    private static final int MAX_TARGET_ID_LENGTH = 64;
    /**
     * RAG 仓储，负责检索策略与绑定的读写，也用于校验时读知识库。
     *
     * <p>所有查询和写入都带租户号，跨租户既读不到也改不了。</p>
     */
    private final IRagRepository repository;
    /**
     * 知识库授权服务，本类每个入口都要求调用者是本租户管理员。
     *
     * <p>连「列出策略」都要求管理员，因为策略参数暴露了平台的检索调优细节，不适合给普通成员看。</p>
     */
    private final RagKnowledgeBaseAuthorizationService authorization;
    /**
     * 绑定目标授权服务，在绑定写库前核对 Agent 或工作流是否真实、是否属于本租户、当前是否可运行。
     *
     * <p>没有它，管理员就能给一个不存在的、别人家的、或者还没发布的目标建绑定，
     * 前者变成垃圾数据，后两者直接是越权和运行时错误的来源。</p>
     */
    private final RagBindingTargetAuthorizationService targetAuthorization;

    /**
     * 由 Spring 注入配置仓储、租户授权与目标授权服务；三者都是必需依赖。
     */
    public RagRetrievalConfigurationService(IRagRepository repository,
                                            RagKnowledgeBaseAuthorizationService authorization,
                                            RagBindingTargetAuthorizationService targetAuthorization) {
        // 保存仓储引用，用于策略和绑定的持久化。
        this.repository = repository;
        // 保存租户授权服务引用，每个入口先做管理员校验。
        this.authorization = authorization;
        // 保存目标授权服务引用，仅在建绑定时用来核对目标可运行。
        this.targetAuthorization = targetAuthorization;
    }

    /**
     * 新建一份检索策略。
     *
     * <p>各层职责：
     * 第一层：管理员校验；
     * 第二层：生成不可猜测的策略编号；
     * 第三层：把请求值映射成领域实体，实体构造过程会完成全部参数合法性校验；
     * 第四层：插入数据库，受影响行数不是 1 就按冲突返回。</p>
     *
     * <p>数据流：管理员请求（策略参数）→ 管理员校验 → 生成策略编号 → 映射成实体（含参数校验）
     * → 插入数据库 → 返回新策略</p>
     *
     * <p>为什么参数校验放在实体构造里而不是这里：策略的参数之间有很多互相约束的规则
     * （例如各阶段召回数量的大小关系、权重之和、阈值范围）。把规则集中在实体上，
     * 创建和更新两条路径就必然共用同一套校验，不可能出现「新建时校验、更新时漏校验」。</p>
     *
     * <p>会写数据库并开事务。主要失败条件：非管理员、参数非法、插入冲突。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagRetrievalProfileEntity createProfile(String tenantId, String userId, String roleCode,
                                                    ProfileValues values) {
        // 第一层：只有本租户管理员能配检索策略。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：生成带 profile_ 前缀的随机编号；去掉 UUID 横线让编号更紧凑，随机性防止被枚举。
        String profileId = "profile_" + UUID.randomUUID().toString().replace("-", "");
        // 第三层：映射成领域实体，revision 从 0 开始。参数不合法会在实体构造里被拦下并转成业务异常。
        RagRetrievalProfileEntity profile = toProfile(tenantId, profileId, 0, values);
        // 第四层：插入数据库；受影响行数不是 1 说明撞上了唯一约束（例如并发同名插入）。
        if (repository.insertRetrievalProfile(tenantId, profile) != 1) {
            // 按创建冲突返回，让管理员换个名字或稍后重试。
            throw new AppException("RAG_PROFILE_CONFLICT", "检索策略创建冲突");
        }
        // 插入成功，把实体返回，前端可直接渲染新策略。
        return profile;
    }

    /**
     * 更新一份检索策略，用 revision 做乐观并发控制。
     *
     * <p>各层职责：
     * 第一层：管理员校验；
     * 第二层：按租户读出现有策略（读不到按不存在）；
     * 第三层：版本号比对，防止基于过期页面覆盖别人的修改；
     * 第四层：把新参数映射成实体（revision 加一），实体构造完成参数校验；
     * 第五层：带 revision 条件更新落库，行数不是 1 则再次按冲突返回。</p>
     *
     * <p>数据流：
     * 管理员请求（策略编号 + 预期版本号 + 新参数）
     * → 管理员校验
     * → 按租户读策略
     * → 版本号比对
     * → 映射成新实体（revision + 1，含参数校验）
     * → revision CAS 更新
     * → 返回更新后的策略</p>
     *
     * <p>为什么前置比对之后还要 CAS：前置比对只能说明「我读到的那一刻是一致的」，
     * 读完到写入之间仍有窗口期。CAS 才是真正的并发保护，前置比对只是为了尽早给出清晰的错误提示。</p>
     *
     * <p>会写数据库并开事务。注意策略是被多个绑定共享的，改它会立刻影响所有引用该策略的 Agent 和工作流。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagRetrievalProfileEntity updateProfile(String tenantId, String userId, String roleCode,
                                                    String profileId, long expectedRevision,
                                                    ProfileValues values) {
        // 第一层：管理员校验，非管理员一次查库都不做。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：按租户读现有策略；requireId 会把空编号统一转成明确的参数错误。
        RagRetrievalProfileEntity existing = repository.findRetrievalProfile(tenantId, requireId(profileId,
                        "RAG_PROFILE_ID_INVALID", "检索策略ID"))
                .orElseThrow(() -> new AppException("RAG_PROFILE_NOT_FOUND", "检索策略不存在"));
        // 第三层：比对版本号，确认管理员是基于最新状态提交的修改。
        if (expectedRevision != existing.revision()) {
            // 不一致就中止，要求刷新后重做，避免静默覆盖别人刚保存的调参结果。
            throw new AppException("RAG_PROFILE_REVISION_CONFLICT", "检索策略已被其他操作更新");
        }
        // 第四层：映射成新实体，revision 在原值上加一；参数非法会在实体构造里被拦下。
        // 策略编号取自已读出的实体而不是入参，确保改的一定是刚刚校验过的那一份。
        RagRetrievalProfileEntity updated = toProfile(tenantId, existing.profileId(), expectedRevision + 1, values);
        // 第五层：带 revision 条件更新；行数不是 1 说明读取之后又有并发修改抢先落库。
        if (repository.updateRetrievalProfile(tenantId, updated, expectedRevision) != 1) {
            // 并发冲突时中止，绝不重试覆盖。
            throw new AppException("RAG_PROFILE_REVISION_CONFLICT", "检索策略已被其他操作更新");
        }
        // 更新成功，返回新实体（带新的 revision），供前端下一次编辑继续做 CAS。
        return updated;
    }

    /**
     * 列出当前租户的全部检索策略。
     *
     * <p>要求管理员权限：策略参数反映了平台的检索调优细节，不适合暴露给普通成员。
     * 租户隔离由仓储查询里的租户号保证。</p>
     *
     * <p>只读，不写库。</p>
     */
    public List<RagRetrievalProfileEntity> listProfiles(String tenantId, String userId, String roleCode) {
        // 管理员校验，普通成员不需要也不应该看到检索参数。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 按租户列出全部策略；隔离靠查询条件里的租户号。
        return repository.listRetrievalProfiles(tenantId);
    }

    /**
     * 新建一条绑定，把「运行目标 + 知识库 + 检索策略 + Token 预算」串起来。
     *
     * <p>各层职责：
     * 第一层：管理员校验，以及绑定参数与目标类型的非空兜底；
     * 第二层：数值边界校验（目标编号长度、Token 预算区间、优先级区间）；
     * 第三层：目标可用性校验，确认这个 Agent 或工作流真实、属于本租户、当前能运行；
     * 第四层：知识库校验，读出实体、确认管理员可管、且当前处于可检索状态；
     * 第五层：策略校验，读出策略实体；
     * 第六层：跨对象一致性校验，绑定的 Token 预算不能超过策略自己的上下文预算；
     * 第七层：组装绑定实体并插入，唯一约束冲突说明这个目标已经绑过同一个知识库。</p>
     *
     * <p>数据流：
     * 管理员请求（目标类型 + 目标 ID + 知识库 ID + 策略 ID + 是否必需 + Token 预算 + 优先级）
     * → 管理员校验
     * → 参数非空与数值边界校验
     * → 目标可用性校验（Agent 查注册与启停 / 工作流查归属与发布）
     * → 读知识库 → 管理员可管校验 → 可检索状态校验
     * → 读检索策略
     * → Token 预算不超过策略预算
     * → 组装绑定实体 → 插入数据库
     * → 返回新绑定</p>
     *
     * <p>关键输入：required 表示这份知识库是不是「必需」——运行时若必需知识库不可用，
     * 对话会直接报错而不是悄悄少查一份资料；priority 决定多个绑定的召回优先顺序；
     * maxTokens 是这条绑定允许占用的上下文预算。</p>
     *
     * <p>会写数据库并开事务。主要失败条件：非管理员、参数为空或越界、目标不存在或不可运行、
     * 知识库不存在或不可绑定、策略不存在、Token 预算超过策略预算、目标已绑过同一个知识库。</p>
     *
     * <p>为什么校验顺序是「先便宜后昂贵」：先做纯内存的参数校验，再依次查目标、知识库、策略。
     * 这样一个明显非法的请求不会白白打出三次数据库查询。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RagAgentBindingEntity createBinding(String tenantId, String userId, String roleCode,
                                               BindingValues values) {
        // 第一层：管理员校验。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 绑定参数整体为空、或没给目标类型，都无法判断该查 Agent 还是查工作流。
        if (values == null || values.targetType() == null) {
            // 参数不完整直接拒绝，不发起任何查询。
            throw new AppException("RAG_BINDING_INVALID", "绑定参数不能为空");
        }
        // 第二层：目标编号去空白并做非空校验；空编号无法核对目标是否存在。
        String targetId = requireId(values.targetId(), "RAG_BINDING_TARGET_INVALID", "绑定目标ID");
        // 数值边界一次校验清：目标编号不能超长（防超长入参压库）；
        // Token 预算必须落在 1 到 32768 之间（下限保证至少能注入一点内容，上限防止一条绑定吃光整个上下文）；
        // 优先级必须落在 0 到 10000 之间（负数和超大值会让多绑定排序出现意料之外的结果）。
        if (targetId.length() > MAX_TARGET_ID_LENGTH || values.maxTokens() < 1 || values.maxTokens() > 32768
                || values.priority() < 0 || values.priority() > 10000) {
            // 任一项越界都按绑定参数非法拒绝，避免把不可用的配置写进线上检索链路。
            throw new AppException("RAG_BINDING_INVALID", "绑定目标、优先级或Token预算非法");
        }
        // 第三层：核对目标真实、属于本租户、且当前可运行。Agent 查平台注册与租户启停，
        // 工作流查租户归属与发布状态；不通过会在里面直接抛异常。
        targetAuthorization.requireAvailable(tenantId, values.targetType(), targetId);
        // 第四层：按租户读知识库；读不到按「不存在」返回，不暴露跨租户资源的存在性。
        RagKnowledgeBaseEntity knowledgeBase = repository.findKnowledgeBase(tenantId,
                        requireId(values.knowledgeBaseId(), "RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库ID"))
                .orElseThrow(() -> new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        // 再确认这个库确实归当前租户、且调用者能管它；这是防止跨租户绑定的第二道保险。
        authorization.requireManageable(tenantId, userId, roleCode, knowledgeBase);
        // 知识库还必须处于可检索状态：删除中、正在重建索引、被停用的库绑上去，运行时只会拿到空结果或直接报错。
        if (!knowledgeBase.status().searchable()) {
            // 状态不可用就拒绝绑定，让管理员先把库恢复正常。
            throw new AppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", "知识库当前不可绑定");
        }
        // 第五层：按租户读检索策略；策略是绑定的必填依赖，读不到直接按不存在返回。
        RagRetrievalProfileEntity profile = repository.findRetrievalProfile(tenantId,
                        requireId(values.profileId(), "RAG_PROFILE_NOT_FOUND", "检索策略ID"))
                .orElseThrow(() -> new AppException("RAG_PROFILE_NOT_FOUND", "检索策略不存在"));
        // 第六层：跨对象一致性校验。绑定的 Token 预算不能超过策略自己声明的上下文预算，
        // 否则运行时会出现「绑定说能用 8000，策略只支持 4000」的矛盾，最终必然被截断，配置形同虚设。
        if (values.maxTokens() > profile.maxContextTokens()) {
            // 预算超限直接拒绝，让管理员要么调小绑定预算，要么先把策略预算调大。
            throw new AppException("RAG_BINDING_INVALID", "绑定Token预算不能超过检索策略预算");
        }
        // 第七层：组装绑定实体。绑定编号随机生成；知识库编号和策略编号都取自刚校验过的实体而不是入参，
        // 保证写进去的一定是校验通过的那两个对象；revision 从 0 开始。
        RagAgentBindingEntity binding = new RagAgentBindingEntity(tenantId,
                "binding_" + UUID.randomUUID().toString().replace("-", ""), values.targetType(), targetId,
                knowledgeBase.knowledgeBaseId(), profile.profileId(), values.required(), values.maxTokens(),
                values.priority(), 0);
        // 插入数据库；行数不是 1 说明撞上唯一约束，也就是这个目标已经绑过同一个知识库。
        if (repository.insertBinding(tenantId, binding) != 1) {
            // 按重复绑定返回明确提示，而不是让管理员看到一条含义模糊的数据库错误。
            throw new AppException("RAG_BINDING_CONFLICT", "当前目标已绑定该知识库");
        }
        // 插入成功，返回新绑定，前端可立即渲染。
        return binding;
    }

    /**
     * 列出当前租户的全部绑定。
     *
     * <p>要求管理员权限。绑定关系暴露了「哪个 Agent 能读哪个知识库」，属于配置信息，不对普通成员开放。</p>
     *
     * <p>只读，不写库。</p>
     */
    public List<RagAgentBindingEntity> listBindings(String tenantId, String userId, String roleCode) {
        // 管理员校验。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 按租户列出全部启用中的绑定；隔离靠查询条件里的租户号。
        return repository.listBindings(tenantId);
    }

    /**
     * 删除一条绑定（软删除），用 revision 做乐观并发控制。
     *
     * <p>各层职责：
     * 第一层：管理员校验；
     * 第二层：按租户读出绑定，读不到按不存在返回；
     * 第三层：版本号比对与 CAS 删除合并成一个判断，任一不成立都按冲突返回。</p>
     *
     * <p>数据流：管理员请求（绑定编号 + 预期版本号）→ 管理员校验 → 按租户读绑定
     * → 版本号比对 + revision CAS 软删除 → 成功则无返回值</p>
     *
     * <p>会写数据库并开事务。删除绑定后，对应的 Agent 或工作流下一轮对话就不再注入这个知识库的内容。</p>
     *
     * <p>为什么是软删除：绑定编号可能已经写进历史消息的证据快照里，物理删除会让历史引用彻底失去上下文；
     * 软删除保留了记录，同时让它退出检索范围。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBinding(String tenantId, String userId, String roleCode,
                              String bindingId, long expectedRevision) {
        // 第一层：管理员校验。
        authorization.requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：按租户读绑定；读不到按不存在返回，不暴露跨租户绑定的存在性。
        RagAgentBindingEntity binding = repository.findBinding(tenantId,
                        requireId(bindingId, "RAG_BINDING_NOT_FOUND", "绑定ID"))
                .orElseThrow(() -> new AppException("RAG_BINDING_NOT_FOUND", "绑定不存在"));
        // 第三层：两个条件用短路或串在一起——先比版本号，不一致就直接判冲突、连删除都不执行；
        // 版本号一致才发起带 revision 条件的软删除，若受影响行数不是 1，说明读取之后又被并发改动过。
        // 无论哪一种，结果都是这次删除不生效。
        if (expectedRevision != binding.revision()
                || repository.deleteBinding(tenantId, binding.bindingId(), expectedRevision) != 1) {
            // 按版本冲突返回，要求管理员刷新后基于最新状态重做，绝不盲目重试删除。
            throw new AppException("RAG_BINDING_REVISION_CONFLICT", "绑定已被其他操作更新");
        }
    }

    /**
     * 把请求参数映射成检索策略实体，并把参数错误转换成稳定的业务错误码。
     *
     * <p>各层职责：
     * 第一层：参数对象非空兜底；
     * 第二层：名称归一（去空白）与限长；
     * 第三层：交给实体构造做全部数值规则校验，把实体抛出的参数异常翻译成业务异常。</p>
     *
     * <p>数据流：请求参数 → 非空校验 → 名称归一与限长 → 实体构造（数值规则校验）
     * → 校验失败则翻译成 RAG_PROFILE_INVALID 抛出</p>
     *
     * <p>为什么要做异常翻译：实体抛的是 IllegalArgumentException，直接透出去会变成 HTTP 500。
     * 翻译成业务错误码后，前端能拿到结构一致的响应，并把实体给出的具体原因原文展示给管理员。</p>
     *
     * <p>纯转换，不写库。创建和更新共用它，保证两条路径的校验口径完全一致。</p>
     */
    private RagRetrievalProfileEntity toProfile(String tenantId, String profileId, long revision,
                                                ProfileValues values) {
        // 第一层：参数对象为空说明调用方用法有问题，直接拒绝。
        if (values == null) throw new AppException("RAG_PROFILE_INVALID", "检索策略参数不能为空");
        // 第二层：名称去首尾空白；null 先归一成空串，交给下一行统一判空，避免这里再写一次空判断。
        String name = values.name() == null ? "" : values.name().trim();
        // 归一后仍为空、或超过长度上限，都算名称非法。
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            // 一次性把「不能为空」和「不能超长」两种原因说清楚，管理员一看提示就知道怎么改。
            throw new AppException("RAG_PROFILE_INVALID", "检索策略名称不能为空且不能超过128字符");
        }
        // 第三层：实体构造里有大量数值规则（各阶段召回数量关系、权重、阈值范围等），可能抛参数异常，
        // 这里必须捕获并翻译，否则会变成没有错误码的系统异常。
        try {
            // 逐项把请求参数交给实体：检索模式与融合策略、稠密与稀疏权重、各阶段召回数量、
            // 是否重排与重排数量、最终条数、相邻块窗口、上下文预算、相似度阈值、是否改写查询、是否去重，
            // 最后带上调用方算好的 revision。所有合法性判断都由实体自己完成。
            return new RagRetrievalProfileEntity(tenantId, profileId, name, values.mode(),
                    values.fusionStrategy(), values.denseWeight(), values.sparseWeight(), values.denseTopK(),
                    values.sparseTopK(), values.fusionTopK(), values.rerankEnabled(), values.rerankTopK(),
                    values.finalTopK(), values.neighborWindow(), values.maxContextTokens(), values.scoreThreshold(),
                    values.queryRewriteEnabled(), values.deduplicateEnabled(), revision);
        // 实体拒绝了这组参数，说明某一项数值不合规或几项之间互相矛盾。
        } catch (IllegalArgumentException exception) {
            // 换成统一的业务错误码，但保留实体给出的具体原因文案，让管理员知道到底是哪一项不对。
            throw new AppException("RAG_PROFILE_INVALID", exception.getMessage());
        }
    }

    /**
     * 校验并清理外部传入的业务编号。
     *
     * <p>错误码和字段名都由调用方传入，这样同一个工具方法可以在策略、绑定、知识库等场景下
     * 给出各自贴切的错误提示，而不是一律报一个笼统的参数错误。</p>
     */
    private String requireId(String value, String code, String name) {
        // null 和空白串都算没传；用调用方指定的错误码抛出，保证提示与所在场景一致。
        if (value == null || value.isBlank()) throw new AppException(code, name + "不能为空");
        // 去掉首尾空白后返回；前端输入框很容易带空格，不清理会导致明明存在却查不到。
        return value.trim();
    }

    /**
     * 检索策略的全部可配置参数，用于创建和更新两条路径的入参承载。
     *
     * <p>包含：策略名；检索模式（只走稠密还是稠密+稀疏混合）；融合策略（多路结果怎么合成一个排序）；
     * 稠密与稀疏的权重；稠密、稀疏、融合三个阶段各召回多少条；是否重排以及重排取多少条；
     * 最终保留多少条；相邻块窗口（命中一块时顺带带出前后几块，保证语义完整）；
     * 上下文 Token 预算；相似度阈值（低于它的候选直接丢掉）；是否改写查询；是否对结果去重。</p>
     *
     * <p>它只是一个传参容器，不做任何校验——所有数值规则都由策略实体在构造时统一判定，
     * 这样创建和更新不可能出现校验口径不一致。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record ProfileValues(String name, RagRetrievalMode mode, RagFusionStrategy fusionStrategy,
                                BigDecimal denseWeight, BigDecimal sparseWeight, int denseTopK, int sparseTopK,
                                int fusionTopK, boolean rerankEnabled, int rerankTopK, int finalTopK,
                                int neighborWindow, int maxContextTokens, BigDecimal scoreThreshold,
                                boolean queryRewriteEnabled, boolean deduplicateEnabled) { }

    /**
     * 新建绑定的入参容器：把哪个知识库、用哪份策略、绑到哪个运行目标上，以及预算和优先级。
     *
     * <p>required 为真表示这是「必需知识库」：运行时它若不可用（被停用、被删、变成私有读不到），
     * 对话会直接报错，而不是悄悄少注入一份资料——因为对某些场景来说，缺了这份资料的回答是不可接受的。</p>
     *
     * <p>priority 决定多条绑定的召回优先顺序；maxTokens 是这条绑定允许占用的上下文预算，
     * 不能超过所选策略自己声明的预算。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record BindingValues(RagBindingTargetType targetType, String targetId, String knowledgeBaseId,
                                String profileId, boolean required, int maxTokens, int priority) { }
}
