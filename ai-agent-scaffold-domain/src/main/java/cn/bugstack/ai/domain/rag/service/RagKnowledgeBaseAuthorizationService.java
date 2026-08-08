package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * RAG 资源的统一授权规则服务。
 * <p>该服务验证租户与用户身份、管理员角色和资源租户归属。所有身份信息
 * 必须由调用方从服务端可信上下文提供，不使用请求体中的非可信身份字段。</p>
 */
@Service
public final class RagKnowledgeBaseAuthorizationService {

    /**
     * 确认当前操作至少带着一个可信的「租户 + 用户」身份。
     *
     * <p>这是所有后续判断的地基：两个身份字段必须都由服务端从登录态解析出来。
     * 只要有一个为空或空白，说明请求根本没有可信身份（例如匿名调用、上下文丢失、内部调用忘了传），
     * 直接抛 RAG_AUTH_CONTEXT_MISSING 中止，绝不允许「没有身份也能往下走」。</p>
     *
     * <p>不写库、不改状态。失败方式只有抛异常，没有返回值。</p>
     */
    public void requireTenantMember(String tenantId, String userId) {
        // 租户号和用户号缺一不可：缺租户就无法做数据隔离，缺用户就无法追责，两者任一为空都视为身份缺失。
        if (isBlank(tenantId) || isBlank(userId)) {
            // 用固定错误码中断整个操作；调用方会把它翻译成「身份信息缺失」提示，而不是继续执行读写。
            throw new AppException("RAG_AUTH_CONTEXT_MISSING", "缺少可信租户或用户身份");
        }
    }

    /**
     * 确认当前身份是本租户的 owner 或 admin，只有他们能维护知识库。
     *
     * <p>先复用成员校验保证身份可信，再看角色码。普通成员只能用知识库做问答，
     * 不能建库、传文档、改检索参数、删资料，所以这里把写操作的门槛卡在管理员。</p>
     *
     * <p>关键输入：roleCode 是服务端算出的角色码，忽略大小写比较，避免前端传 "Owner" 就被判失败。</p>
     *
     * <p>不写库、不改状态。角色不够时抛 RAG_ADMIN_REQUIRED，操作不会产生任何副作用。</p>
     */
    public void requireTenantAdministrator(String tenantId, String userId, String roleCode) {
        // 先过一遍身份可信校验；身份都不完整就没必要讨论角色，直接在上一层中断。
        requireTenantMember(tenantId, userId);
        // 只认 owner 和 admin 两种角色，忽略大小写；其他角色（普通成员、访客）一律没有维护权限。
        if (!"owner".equalsIgnoreCase(roleCode) && !"admin".equalsIgnoreCase(roleCode)) {
            // 身份是真的但权限不够，用专门的错误码区分开，前端才能提示「请联系管理员」而不是「请重新登录」。
            throw new AppException("RAG_ADMIN_REQUIRED", "仅租户管理员可以维护知识库");
        }
    }

    /**
     * 确认「当前身份是管理员」且「这个知识库真的属于当前租户」，是写操作前的完整门禁。
     *
     * <p>各层职责：
     * 第一层：管理员校验，把非管理员和无身份请求挡在外面；
     * 第二层：租户归属校验，把「查得到但不是自己家的知识库」也挡掉。</p>
     *
     * <p>数据流：
     * 可信身份 + 已查出的知识库实体
     * → 管理员校验（不通过则抛权限异常）
     * → 比对知识库上的租户号与当前租户号
     * → 不一致或实体为空则统一按「不存在」返回
     * → 通过后调用方才可以继续改数据</p>
     *
     * <p>关键输入：knowledgeBase 是调用方先查出来的实体，允许为 null（查不到）。</p>
     *
     * <p>为什么「不存在」和「越权」共用一个错误码：如果越权时提示「无权访问」，攻击者就能靠错误码差异
     * 逐个探测出别家租户到底有哪些知识库 ID。统一成「不存在或无权访问」，对方什么也探测不出来。</p>
     *
     * <p>不写库、不改状态。</p>
     */
    public void requireManageable(String tenantId, String userId, String roleCode,
                                  RagKnowledgeBaseEntity knowledgeBase) {
        // 第一层：先确认身份可信且是租户管理员；普通成员到这里就被挡住，不会看到任何知识库信息。
        requireTenantAdministrator(tenantId, userId, roleCode);
        // 第二层：实体为空（库里没这条）和租户号不匹配（是别家租户的）两种情况合并处理，
        // 因为它们对调用方来说都必须表现成同一个结果，否则错误码差异会泄露别人的数据是否存在。
        if (knowledgeBase == null || !tenantId.equals(knowledgeBase.tenantId())) {
            // 统一按「知识库不存在或无权访问」中断，既阻止越权修改，也不暴露跨租户资源的存在性。
            throw new AppException("RAG_KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在或无权访问");
        }
    }

    /**
     * 确认某个已查出的 RAG 资源（文档、版本、绑定、检索配置等）确实挂在当前租户名下。
     *
     * <p>用在「先按 ID 查出实体，再动它」的场景：只要实体上记录的租户号和当前可信租户号不一致，
     * 就说明这是一次跨租户访问，必须立刻中断，不能因为 ID 猜对了就放行。</p>
     *
     * <p>关键输入：tenantId 是可信租户号，resourceTenantId 是实体自己带的租户号；任一为空也算校验失败，
     * 因为空值比较很容易被误当成「相等」而放过越权请求。</p>
     *
     * <p>不写库、不改状态。失败抛 RAG_TENANT_SCOPE_MISMATCH。</p>
     */
    public void requireTenantScope(String tenantId, String resourceTenantId) {
        // 三个条件缺一不可：当前租户号不能空、资源租户号不能空、两者必须完全相等；
        // 任一不满足都说明这次访问跨出了租户边界，后续读写一律不允许发生。
        if (isBlank(tenantId) || isBlank(resourceTenantId) || !tenantId.equals(resourceTenantId)) {
            // 用专门的越界错误码中断，便于在日志和告警里把「跨租户访问」这类安全事件单独统计出来。
            throw new AppException("RAG_TENANT_SCOPE_MISMATCH", "RAG 资源不属于当前租户");
        }
    }

    /**
     * 统一判断一个可信身份字段是不是「等于没传」。
     *
     * <p>把 null 和纯空白（例如空串、只有空格）都算作缺失，防止调用方传个空格就绕过身份校验。</p>
     */
    private boolean isBlank(String value) {
        // null 和空白串都判为缺失：身份字段只要不是实实在在的值，就不能拿来做隔离和鉴权。
        return value == null || value.isBlank();
    }
}
