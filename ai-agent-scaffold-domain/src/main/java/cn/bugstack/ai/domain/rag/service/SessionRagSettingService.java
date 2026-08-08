package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.ISessionRagSelectionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagEligibleBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagRunSnapshotEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会话级 RAG 选择与运行快照服务。
 * <p>OFF 模式不使用 RAG；AUTO 模式使用运行目标当前全部可用绑定；MANUAL 模式
 * 只使用用户选择且目标当前仍可用的绑定。服务不复制目标授权关系，每次查询与更新都重新校验
 * 绑定、知识库、检索配置和资源可见性。创建运行时将生效策略冻结为不可变快照。</p>
 */
@Service
public class SessionRagSettingService {

    /**
     * 手动模式下单个会话最多能勾选的绑定数量，32 个。
     *
     * <p>限制原因有两个：一是运行快照会被写进运行记录，无上限会让快照无界膨胀；
     * 二是每个绑定都要占一份上下文预算，选太多必然被裁剪，选了也用不上。</p>
     */
    private static final int MAX_SELECTED_BINDINGS = 32;
    /**
     * 会话域，负责会话的访问控制、加锁读取，以及 RAG 策略字段的 revision 推进。
     *
     * <p>查询走「校验访问」，更新走「加锁读取」——加锁是为了防止两个客户端同时改同一个会话的 RAG 设置，
     * 造成模式和勾选内容对不上（例如模式写成了 OFF，勾选却留着一堆绑定）。</p>
     */
    private final SessionDomain sessionDomain;
    /**
     * RAG 仓储，用来实时查询目标绑定、知识库和检索策略的当前状态。
     *
     * <p>刻意每次都实时查而不缓存：知识库随时可能被停用、删除或改成私有，
     * 缓存会让用户在设置页看到一个其实已经不能用的库，甚至让运行时才发现资料读不到。</p>
     */
    private final IRagRepository ragRepository;
    /**
     * 会话选择仓储，只存「这个会话勾选了哪些 bindingId」。
     *
     * <p>单独一张表的意义在于：它只是一份「用户偏好」，不承载任何权限含义。
     * 每次使用前都会拿它和目标当前真实可用的绑定做交集校验，所以即使里面残留了已被解绑的编号，也读不到资料。</p>
     */
    private final ISessionRagSelectionRepository selectionRepository;

    /**
     * 由 Spring 注入会话域、RAG 仓储和会话选择仓储；三者都是必需依赖。
     */
    public SessionRagSettingService(SessionDomain sessionDomain, IRagRepository ragRepository,
                                    ISessionRagSelectionRepository selectionRepository) {
        // 保存会话域引用，负责访问控制、加锁与策略 revision 推进。
        this.sessionDomain = sessionDomain;
        // 保存 RAG 仓储引用，用于实时查询绑定与知识库状态。
        this.ragRepository = ragRepository;
        // 保存会话选择仓储引用，用于读写用户的手动勾选。
        this.selectionRepository = selectionRepository;
    }

    /**
     * 查询某个会话的 RAG 设置，以及当前真实可用的绑定列表。
     *
     * <p>数据流：租户 + 用户 + 会话 → 会话访问权校验 → 实时汇总模式、可用绑定、当前勾选、是否配置可用 → 返回</p>
     *
     * <p>只读，不写库。返回结果里的可用绑定是实时查出来的，所以刚被管理员停用的库会立刻从列表消失。</p>
     */
    public SessionRagSettingEntity query(String tenantId, String userId, String sessionId) {
        // 先校验这个用户有权访问该会话，并顺便拿到会话实体；越权请求到这里就结束。
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        // 基于会话实体实时汇总出完整设置，包括当前真正可用的绑定。
        return toSetting(session);
    }

    /**
     * 把会话的 RAG 策略定格成一份「本轮运行不会再变」的快照。
     *
     * <p>各层职责：
     * 第一层：会话非空兜底；
     * 第二层：实时汇总当前设置；
     * 第三层：OFF 模式直接返回空快照，本轮完全不走 RAG；
     * 第四层：按模式算出本轮真正要用的绑定集合（AUTO 展开全部可用，MANUAL 用用户勾选）；
     * 第五层：再拿可用集合做一次交集校验，空集或存在不可用项都直接失败。</p>
     *
     * <p>数据流：
     * 会话实体
     * → 非空校验
     * → 实时汇总设置（模式 + 可用绑定 + 勾选）
     * → OFF：返回空快照
     * → AUTO：取全部可用绑定编号；MANUAL：取用户勾选
     * → 与可用集合求交集校验
     * → 返回快照（模式 + 策略 revision + 绑定编号列表）</p>
     *
     * <p>为什么必须定格成快照：一轮对话可能调好几次模型、跑好几个工作流节点。如果每次都重新算一遍可用绑定，
     * 管理员中途解绑或停用一个库，就会出现「前半段回答引用了它、后半段引用不到」的漂移，
     * 最终引用校验还会把前半段的引用判成伪造。定格之后，本轮范围固定不变。</p>
     *
     * <p>为什么这里就要失败而不是等运行时：这一步发生在调用大模型之前。此刻失败还能明确告诉用户
     * 「没有可用的知识库，请关掉 RAG 或重新选」；等到模型调用中途才发现，就只能返回一个语义不明的错误。</p>
     *
     * <p>只读，不写库。</p>
     */
    public SessionRagRunSnapshotEntity resolveRunSnapshot(ChatSessionEntity session) {
        // 第一层：会话为空说明调用方用法有问题，用非法参数异常直接暴露，而不是悄悄返回空快照。
        if (session == null) {
            // 抛非法参数异常终止；这是内部调用契约问题，不是用户可修复的业务错误。
            throw new IllegalArgumentException("会话不能为空");
        }
        // 第二层：实时汇总当前设置，可用绑定就是在这一步查出来的。
        SessionRagSettingEntity setting = toSetting(session);
        // 第三层：会话明确关掉了 RAG。
        if (setting.mode() == SessionRagMode.OFF) {
            // 返回一个空快照（模式 OFF、绑定列表为空），本轮不注入任何资料，也不算失败。
            return new SessionRagRunSnapshotEntity(SessionRagMode.OFF,
                    RagInvocationMode.resolve(session.getRagInvocationMode()), setting.revision(), List.of());
        }
        // 第四层：按模式决定本轮用哪些绑定——AUTO 展开成当前全部可用绑定的编号（管理员新加的库自动生效），
        // MANUAL 用用户自己勾选的那几个（新加的库不会自动进来）。
        List<String> effective = setting.mode() == SessionRagMode.AUTO
                ? setting.eligibleBindings().stream().map(SessionRagEligibleBindingEntity::bindingId).toList()
                : setting.selectedBindingIds();
        // 把「当前真正可用」的绑定编号收成集合，作为下一步的校验依据。
        var eligibleIds = setting.eligibleBindings().stream()
                .map(SessionRagEligibleBindingEntity::bindingId).collect(Collectors.toSet());
        // 第五层：两种情况都不能放行——一个都没有（库全被停用 / 删除，或勾选内容已全部失效），
        // 或者勾选里有不在可用集合内的编号（管理员解绑了，但会话里还残留着旧勾选）。
        // 这道交集校验是「不复制权限」这个设计的落脚点：残留的勾选永远换不出真实资料。
        if (effective.isEmpty() || !eligibleIds.containsAll(effective)) {
            // 明确报「没有可用的 RAG 绑定」，并提示用户关掉 RAG 或重新选择，而不是静默降级成不带资料的回答——
            // 静默降级会让用户以为回答是有资料依据的。
            throw new AppException("SESSION_RAG_BINDING_UNAVAILABLE",
                    "当前会话没有可用的RAG绑定，请关闭RAG或重新选择绑定");
        }
        // 校验通过，返回定格快照：模式 + 策略 revision（用于事后追溯当时用的是哪一版设置）+ 绑定编号列表。
        return new SessionRagRunSnapshotEntity(setting.mode(),
                RagInvocationMode.resolve(session.getRagInvocationMode()), setting.revision(), effective);
    }

    /**
     * 兼容旧客户端的开关式更新入口：只传一个布尔值开或关。
     *
     * <p>转调完整方法，模式留空、勾选留空、预期版本留空，由完整方法把布尔值翻译成 AUTO 或 OFF。
     * 老客户端不认识三态模式，所以这里保留一个最简形态。</p>
     *
     * <p>会写数据库（走完整方法）。</p>
     */
    public SessionRagSettingEntity update(String tenantId, String userId, String sessionId, boolean enabled) {
        // 直接转调完整方法，保证新旧两条入口共用同一套校验与写入逻辑，不会出现行为差异。
        return update(tenantId, userId, sessionId, null, enabled, null, null);
    }

    /**
     * 更新会话的 RAG 模式，并整体替换手动勾选的绑定。
     *
     * <p>各层职责：
     * 第一层：把「新三态模式」和「旧布尔开关」两种表达合并成一个模式，冲突则拒绝；
     * 第二层：加锁读取会话，防止并发修改导致模式与勾选内容不一致；
     * 第三层：判断目标类型（Agent 还是工作流），并实时查出当前真正可用的绑定；
     * 第四层：校验勾选内容（非空、去重、限量、必须全部在可用集合内），非 MANUAL 模式不许带勾选；
     * 第五层：推进会话上的 RAG 策略（带预期版本做乐观并发控制）；
     * 第六层：整体替换勾选记录，然后重新汇总并记一条审计日志。</p>
     *
     * <p>数据流：
     * 客户端请求（模式 / 旧开关 / 勾选列表 / 预期版本）
     * → 模式归一与冲突校验
     * → 加锁读会话
     * → 判目标类型 → 实时查可用绑定
     * → 勾选校验（得到去重后的最终勾选）
     * → 更新会话 RAG 策略（revision 乐观锁）
     * → 整体替换勾选记录
     * → 重新汇总设置 → 记审计日志 → 返回</p>
     *
     * <p>为什么勾选校验要排在写库之前：先写后校验的话，一旦校验失败就得回滚，
     * 而且中间那一瞬间数据库里存的是一份非法配置。当前顺序保证数据库里永远只有合法配置。</p>
     *
     * <p>为什么勾选是「整体替换」而不是增量：增量更新需要客户端和服务端对当前状态达成一致，
     * 一旦有一次请求丢失就会永久错位。整体替换让每次请求的结果都是确定的。</p>
     *
     * <p>会写数据库并开事务，同时对会话加锁。主要失败条件：模式非法或与旧开关冲突、无权访问会话、
     * 勾选为空 / 重复 / 超量 / 含不可用绑定、预期版本不匹配。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public SessionRagSettingEntity update(String tenantId, String userId, String sessionId,
                                          String requestedMode, Boolean legacyEnabled,
                                          List<String> selectedBindingIds, Long expectedRevision) {
        return update(tenantId, userId, sessionId, requestedMode, null, legacyEnabled,
                selectedBindingIds, expectedRevision);
    }

    /** 更新绑定范围及其正交的调用方式；调用方式缺失时保留锁内当前值。 */
    @Transactional(rollbackFor = Exception.class)
    public SessionRagSettingEntity update(String tenantId, String userId, String sessionId,
                                          String requestedMode, String requestedInvocationMode,
                                          Boolean legacyEnabled, List<String> selectedBindingIds,
                                          Long expectedRevision) {
        // 第一层：把新模式字段和旧布尔开关合并成一个确定的模式；两者冲突会在里面直接拒绝。
        SessionRagMode mode = resolveRequestedMode(requestedMode, legacyEnabled);
        // 第二层：加锁读取会话。加锁而不是普通读取，是为了让并发的两次设置修改串行化，
        // 否则可能出现「模式被改成 OFF，勾选却被另一个请求写成一堆绑定」的矛盾状态。
        ChatSessionEntity locked = sessionDomain.lockSessionAccess(tenantId, userId, sessionId, null);
        // 第三层：按会话来源判断绑定目标类型——工作流会话查工作流绑定，普通会话查 Agent 绑定。
        RagBindingTargetType targetType = targetType(locked);
        // 实时查出当前真正可用的绑定，并只取绑定实体本身（知识库和策略在校验阶段用不到）。
        // 这份列表是下一步校验勾选合法性的唯一依据。
        List<RagAgentBindingEntity> eligible = eligibleBindings(locked, targetType).stream()
                .map(EligibleBinding::binding).toList();
        // 第四层：校验勾选内容并得到去重后的最终列表；非 MANUAL 模式必须不带勾选。
        List<String> selected = validateSelections(mode, selectedBindingIds, eligible);
        // 第五层：推进会话上的 RAG 策略字段，预期版本作为乐观并发条件；不匹配会在会话域里抛冲突。
        // 返回的是更新后的会话实体，后面统一用它的字段而不是入参，确保写入和返回都基于同一份可信数据。
        ChatSessionEntity session = sessionDomain.updateRagPolicy(tenantId, userId, sessionId, mode,
                requestedInvocationMode, expectedRevision);
        // 第六层：整体替换这个会话的勾选记录。用会话实体里的租户、用户、会话编号，
        // 而不是直接用入参，避免入参与实际会话归属不一致时把记录写到别处。
        selectionRepository.replaceSelections(session.getTenantId(), session.getUserId(), session.getSessionId(),
                targetType, session.getAgentId(), selected);
        // 重新实时汇总一次设置：这样返回给客户端的可用绑定、勾选状态、是否配置可用都是写入后的真实结果。
        SessionRagSettingEntity setting = toSetting(session);
        // 记一条结构化审计日志，包含开关状态和是否已配置可用绑定，便于排查「用户说开了 RAG 但没生效」这类问题。
        AiLog.info(AiLog.chat().ragSettingChanged(session.getTenantId(), session.getUserId(),
                session.getSessionId(), setting.enabled(), setting.bindingConfigured()));
        // 返回最新设置，客户端据此刷新界面（含新的 revision，供下一次修改做乐观锁）。
        return setting;
    }

    /**
     * 汇总一个会话的完整 RAG 视图：模式、当前可用绑定、当前勾选、以及配置是否真的可用。
     *
     * <p>各层职责：
     * 第一层：判目标类型并解析出三态模式（兼容只有旧布尔开关的历史会话）；
     * 第二层：实时查出当前可用绑定，并读出用户的勾选记录；
     * 第三层：算「配置是否可用」——MANUAL 要求勾选非空且全部仍然可用，AUTO / OFF 只要求存在可用绑定；
     * 第四层：把可用绑定逐条转换成给前端展示的摘要，并标出哪些已被勾选。</p>
     *
     * <p>数据流：
     * 会话实体
     * → 判目标类型 + 解析模式
     * → 实时查可用绑定 + 读勾选记录
     * → 计算配置可用性
     * → 逐条转换成展示摘要（含是否勾选标记）
     * → 返回完整设置</p>
     *
     * <p>为什么 bindingConfigured 要单独算：模式是 AUTO 不代表真的能用——目标可能压根没配绑定，
     * 或者所有库都被停用了。前端需要这个标记来提示「已开启但暂无可用知识库」。</p>
     *
     * <p>只读，不写库。</p>
     */
    private SessionRagSettingEntity toSetting(ChatSessionEntity session) {
        // 第一层：按会话来源判断该查 Agent 绑定还是工作流绑定。
        RagBindingTargetType targetType = targetType(session);
        // 解析三态模式；历史会话可能只有旧的布尔开关字段，解析逻辑会把它翻译成 AUTO 或 OFF。
        SessionRagMode mode = SessionRagMode.resolve(session.getRagMode(), session.getRagEnabled());
        // 第二层：实时查出当前真正可用的绑定（含知识库和检索策略实体，后面展示摘要要用）。
        List<EligibleBinding> eligible = eligibleBindings(session, targetType);
        // 读出这个会话的勾选记录。它只是用户偏好，可能包含已经失效的编号，所以后面必须和可用集合求交集。
        List<String> selected = selectionRepository.listSelectedBindingIds(
                session.getTenantId(), session.getUserId(), session.getSessionId());
        // 转成保序去重集合，后面判断某个绑定「是否被勾选」时是 O(1) 查找，而不是每次都遍历列表。
        var selectedSet = new LinkedHashSet<>(selected);
        // 把可用绑定的编号收成集合，用于判断勾选内容是否仍然全部有效。
        var eligibleIds = eligible.stream().map(value -> value.binding().bindingId()).collect(Collectors.toSet());
        // 第三层：算「这份配置现在真的能用吗」。MANUAL 模式要求勾选非空、且每一个都仍在可用集合里
        // （少一个就说明用户选的库被解绑或停用了）；AUTO 和 OFF 模式只要求目标至少还有一个可用绑定。
        // 前端拿这个标记提示「已开启但暂无可用知识库」，避免用户以为回答有资料依据。
        boolean configured = mode == SessionRagMode.MANUAL
                ? !selected.isEmpty() && eligibleIds.containsAll(selected)
                : !eligible.isEmpty();
        // 第四层：把可用绑定逐条转成前端展示摘要——绑定编号、知识库编号与名称、策略编号与名称、
        // 知识库当前状态（前端据此显示「正在建索引」这类提示）、是否必需、Token 预算、优先级、
        // 绑定 revision（供解绑时做乐观锁），最后一项是「这条是否已被本会话勾选」。
        // 注意这里只暴露展示所需字段，不暴露向量集合别名等内部信息。
        List<SessionRagEligibleBindingEntity> summaries = eligible.stream()
                .map(value -> new SessionRagEligibleBindingEntity(value.binding().bindingId(),
                        value.knowledgeBase().knowledgeBaseId(), value.knowledgeBase().name(),
                        value.profile().profileId(), value.profile().name(),
                        value.knowledgeBase().status().name(), value.binding().required(),
                        value.binding().maxTokens(), value.binding().priority(),
                        value.binding().revision(), selectedSet.contains(value.binding().bindingId())))
                .toList();
        // 组装完整设置返回：会话编号、兼容用的布尔开关、三态模式、
        // 策略 revision（为 null 的历史会话按 0 处理，保证客户端拿到的乐观锁初值可用）、
        // 配置可用标记、目标类型与目标编号、当前勾选、可用绑定摘要。
        return new SessionRagSettingEntity(session.getSessionId(), mode.enabled(), mode,
                RagInvocationMode.resolve(session.getRagInvocationMode()),
                session.getRagRevision() == null ? 0L : session.getRagRevision(), configured,
                targetType, session.getAgentId(), selected, summaries);
    }

    /**
     * 把「新三态模式」和「旧布尔开关」两种表达合并成一个确定的模式。
     *
     * <p>各层职责：
     * 第一层：两者都没传则拒绝，避免把「没说」当成「关掉」；
     * 第二层：只有旧开关时，true 翻译成 AUTO、false 翻译成 OFF；
     * 第三层：有新模式时按枚举解析，不认识的值明确报错；
     * 第四层：两者都传时必须语义一致，冲突则拒绝。</p>
     *
     * <p>数据流：模式字符串 + 旧开关 → 都为空则报错 → 只有开关则映射成 AUTO/OFF
     * → 有模式则解析枚举（失败报错）→ 与开关比对一致性 → 返回模式</p>
     *
     * <p>为什么冲突要报错而不是以某一方为准：客户端同时传了 mode=OFF 和 enabled=true，说明它内部状态已经乱了。
     * 这时无论听谁的都可能违背用户真实意图，明确拒绝反而更安全。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private SessionRagMode resolveRequestedMode(String requestedMode, Boolean legacyEnabled) {
        // 第一层：两个字段都没给，无法判断用户想要什么。绝不默认成关闭——那会悄悄把用户开着的 RAG 关掉。
        if ((requestedMode == null || requestedMode.isBlank()) && legacyEnabled == null) {
            // 用统一的非法参数错误码报错，提示至少要给出模式或开关之一。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式或开关不能为空");
        }
        // 先声明最终模式变量，两条分支各自给它赋值。
        SessionRagMode mode;
        // 第二层：只有旧布尔开关，说明是老客户端。
        if (requestedMode == null || requestedMode.isBlank()) {
            // true 映射成 AUTO（用目标当前全部可用绑定），false 映射成 OFF。
            // 老客户端没有手动选择的概念，所以映射成 AUTO 而不是 MANUAL。
            mode = Boolean.TRUE.equals(legacyEnabled) ? SessionRagMode.AUTO : SessionRagMode.OFF;
        } else {
            // 第三层：有新模式字符串，按枚举解析可能抛异常，需要单独捕获转成业务错误。
            try {
                // 去空白并转大写后解析，容忍客户端传小写或带空格的写法。
                mode = SessionRagMode.valueOf(requestedMode.trim().toUpperCase());
            // 解析失败说明传了一个平台不认识的模式值。
            } catch (IllegalArgumentException exception) {
                // 明确列出三个合法值，让客户端一看提示就知道该传什么，而不是收到一个笼统的参数错误。
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式仅支持OFF、AUTO或MANUAL");
            }
        }
        // 第四层：两个字段都传了，就必须语义一致——OFF 对应 false，AUTO 和 MANUAL 对应 true。
        if (legacyEnabled != null && legacyEnabled != mode.enabled()) {
            // 冲突时拒绝：客户端内部状态已经不一致，此时听谁的都可能违背用户真实意图。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式与兼容开关冲突");
        }
        // 返回唯一确定的模式，后续流程只依赖它，不再看原始入参。
        return mode;
    }

    /**
     * 校验并归一手动勾选的绑定编号。
     *
     * <p>各层职责：
     * 第一层：把入参归一成去空白后的字符串列表（null 当空列表）；
     * 第二层：非 MANUAL 模式不允许带任何有效勾选，带了就报错而不是静默丢弃；
     * 第三层：MANUAL 模式要求至少一个、且不能有空串；
     * 第四层：数量不超过上限；
     * 第五层：不允许重复；
     * 第六层：每一个都必须在当前可用绑定集合内。</p>
     *
     * <p>数据流：请求勾选 → 去空白归一 → 非 MANUAL 则要求为空并返回空列表
     * → 非空与无空串校验 → 数量上限校验 → 去重校验 → 可用性校验 → 返回去重后的有序列表</p>
     *
     * <p>为什么非 MANUAL 带勾选要报错：静默忽略会让用户以为自己选的库生效了，实际却按 AUTO 全量或完全不用。
     * 明确报错能让客户端立刻发现自己发错了参数。</p>
     *
     * <p>为什么要做可用性校验：这是「不把知识库权限复制到会话」的关键一步。用户只能选目标当前确实有权读的库，
     * 任何越权或已失效的编号都会在这里被拒。</p>
     *
     * <p>纯计算，不写库。</p>
     */
    private List<String> validateSelections(SessionRagMode mode, List<String> requested,
                                            List<RagAgentBindingEntity> eligible) {
        // 第一层：归一入参——null 当空列表，每一项去掉首尾空白，null 项换成空串交给后面统一判空。
        // 先归一再校验，避免「   」这种全空白输入被当成合法编号。
        List<String> values = requested == null ? List.of() : requested.stream()
                .map(value -> value == null ? "" : value.trim()).toList();
        // 第二层：非 MANUAL 模式（OFF 或 AUTO）本来就不该带勾选。
        if (mode != SessionRagMode.MANUAL) {
            // 只要有一项是非空的，就说明客户端传错了参数（全空串视为「没传」，可以容忍）。
            if (values.stream().anyMatch(value -> !value.isEmpty())) {
                // 明确报错而不是静默丢弃，否则用户会以为自己勾选的库生效了。
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "仅MANUAL模式可以选择绑定");
            }
            // 非 MANUAL 模式统一返回空列表，后面会把勾选记录整体清空。
            return List.of();
        }
        // 第三层：MANUAL 模式必须至少选一个，且不允许出现空串（空串无法对应任何绑定）。
        if (values.isEmpty() || values.stream().anyMatch(String::isEmpty)) {
            // 一个都没选却要用 MANUAL 模式，等价于「开了 RAG 但什么都不查」，明确拒绝让用户改成 OFF 或 AUTO。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "MANUAL模式至少选择一个绑定");
        }
        // 第四层：数量上限。超量会让运行快照膨胀，而且每个绑定都要占上下文预算，选太多也用不上。
        if (values.size() > MAX_SELECTED_BINDINGS) {
            // 超量直接拒绝，让用户自己精简选择，而不是由系统截断后选出用户没预料到的组合。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "会话最多选择32个RAG绑定");
        }
        // 第五层：转成保序去重集合，既保留用户的选择顺序，又能通过大小比较发现重复。
        LinkedHashSet<String> unique = new LinkedHashSet<>(values);
        // 去重后数量变少，说明入参里有重复编号。
        if (unique.size() != values.size()) {
            // 明确拒绝重复，而不是悄悄去重：重复通常意味着客户端状态出了问题，值得让它知道。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG绑定ID不能重复");
        }
        // 第六层：把当前可用绑定按编号建成映射，用于判断勾选内容是否全部合法。
        // 建映射（而不是只建编号集合）是因为 toMap 会在出现重复键时抛错，顺带校验了上游数据的唯一性。
        Map<String, RagAgentBindingEntity> eligibleById = eligible.stream()
                .collect(Collectors.toMap(RagAgentBindingEntity::bindingId, Function.identity()));
        // 只要有一个勾选编号不在可用集合里，就说明它不属于当前租户和运行目标，或者已经被解绑 / 停用。
        if (!eligibleById.keySet().containsAll(unique)) {
            // 明确拒绝，这就是防止「会话里残留的旧勾选变成越权入口」的那道闸。
            throw new AppException("SESSION_RAG_BINDING_UNAVAILABLE", "所选绑定不属于当前租户和运行目标或已停用");
        }
        // 返回去重后的不可变有序列表，作为要写库的最终勾选内容。
        return List.copyOf(unique);
    }

    /**
     * 根据会话来源判断绑定目标类型。
     *
     * <p>会话来源标成 workflow 的按工作流查绑定，其余（包括来源缺失的历史会话）都按 Agent 处理。
     * 忽略大小写比较，容忍历史数据里大小写不统一。</p>
     */
    private RagBindingTargetType targetType(ChatSessionEntity session) {
        // 只有来源明确是工作流时才按工作流查绑定；其余情况（含来源为空的老会话）一律按 Agent，
        // 这样历史数据不会因为缺字段而查不到任何绑定。
        return "workflow".equalsIgnoreCase(session.getSourceType())
                ? RagBindingTargetType.WORKFLOW : RagBindingTargetType.AGENT;
    }

    /**
     * 实时查出这个会话当前真正能用的绑定。
     *
     * <p>各层职责：
     * 第一层：按租户和目标查出全部启用绑定；
     * 第二层：逐条把绑定指向的知识库和检索策略查出来，任一缺失就丢弃这条绑定；
     * 第三层：对知识库做三项可用性判断——状态可检索、已经有至少一代索引、可见性允许当前用户读；
     * 第四层：过滤掉被判为不可用的项，返回三者齐全的候选。</p>
     *
     * <p>数据流：
     * 租户 + 目标类型 + 目标编号
     * → 查全部绑定
     * → 逐条查知识库与检索策略
     * → 任一缺失 → 丢弃
     * → 知识库状态可检索 且 已有索引代次 且 可见性通过 → 保留
     * → 过滤空值
     * → 返回候选列表</p>
     *
     * <p>为什么要求代次大于 0：代次为 0 表示这个库还没有任何一代索引建成（刚建库、或第一份文档还在处理）。
     * 这种库检索出来必然是空的，出现在可选列表里只会让用户误以为已经能用。</p>
     *
     * <p>为什么私有库要比对拥有者：私有库只有上传者本人能读。若不比对，同租户其他人就能通过绑定读到别人的私有资料。
     * 注意租户隔离不靠这里，而是靠每个查询都带 tenantId。</p>
     *
     * <p>为什么策略缺失也要丢弃：绑定必须有可用的检索策略才能真正执行检索。
     * 策略被删（或数据不一致）时保留这条绑定，只会让运行时报一个更难理解的错误。</p>
     *
     * <p>只读，不写库。每条绑定会各自查一次知识库和策略，属于典型的 N+1 查询；
     * 单个目标的绑定数量有限，目前可以接受。</p>
     */
    private List<EligibleBinding> eligibleBindings(ChatSessionEntity session,
                                                   RagBindingTargetType targetType) {
        // 第一层：按「租户 + 目标类型 + 目标编号」查出全部启用绑定，查询带租户号，跨租户查不到。
        return ragRepository.listBindings(session.getTenantId(), targetType, session.getAgentId()).stream()
                .map(binding -> {
                    // 第二层：逐条把绑定指向的知识库查出来（仍然带租户号，防止绑定里存了跨租户编号）。
                    var knowledgeBase = ragRepository.findKnowledgeBase(
                            session.getTenantId(), binding.knowledgeBaseId());
                    // 再查它指定的检索策略；策略决定这条绑定实际怎么检索，缺了就没法执行。
                    var profile = ragRepository.findRetrievalProfile(
                            session.getTenantId(), binding.retrievalProfileId());
                    // 任一缺失说明数据不一致（库或策略被删了，绑定却还在）。
                    if (knowledgeBase.isEmpty() || profile.isEmpty()) {
                        // 返回 null 表示丢弃这条绑定，最后统一被过滤掉；不抛异常，避免一条脏数据让整个设置页打不开。
                        return null;
                    }
                    // 取出知识库实体，接下来对它做三项可用性判断。
                    RagKnowledgeBaseEntity value = knowledgeBase.get();
                    // 第三层：三项条件必须同时成立才算这条绑定可用——
                    // 一，知识库状态允许检索（删除中、停用、正在重建索引都不行）；
                    // 二，当前代次大于 0，说明至少已经建成过一代索引，检索出来才可能有内容；
                    // 三，可见性通过：非私有库直接放行，私有库必须是当前用户自己的。
                    // 全部满足则组装成候选（绑定 + 知识库 + 策略），否则同样返回 null 被过滤掉。
                    return value.status().searchable() && value.currentGeneration() > 0
                            && (value.visibility() != RagVisibility.PRIVATE
                            || value.ownerUserId().equals(session.getUserId()))
                            ? new EligibleBinding(binding, value, profile.get()) : null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 一条同时通过了「绑定存在、知识库可检索且可见、检索策略存在」三项校验的候选。
     *
     * <p>把三个实体打包在一起，是为了让上层组装展示摘要时不必再查一遍库——
     * 知识库名和策略名都要显示给用户，重复查询纯属浪费。</p>
     *
     * <p>不可变值对象，只在方法之间传递，不涉及持久化。</p>
     */
    private record EligibleBinding(RagAgentBindingEntity binding,
                                   RagKnowledgeBaseEntity knowledgeBase,
                                   RagRetrievalProfileEntity profile) {
    }
}
