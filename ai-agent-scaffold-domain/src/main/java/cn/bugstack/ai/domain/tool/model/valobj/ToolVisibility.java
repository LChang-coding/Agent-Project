package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具可见范围的字面量常量：决定一个 Skill/MCP 是「只有我能用」还是「全公司能用」。
 *
 * <p>所属层次：工具领域的值对象，取值与数据库 visibility 列一一对应。</p>
 *
 * <p>谁会用它：{@code ToolPublishService} 在创建工具时用它校正默认值、在改动工具时用它判断权限；
 * 仓储的可用工具查询也按这个值决定把哪些工具放进当前用户的可调用目录。</p>
 *
 * <p>为什么它是安全边界：可见范围一旦被写成租户公开，同租户所有人都能调用这个工具，
 * 包括它背后配置的外部接口和凭证。所以只有 owner/admin 能创建或修改租户公开工具，普通成员只能建私有工具。</p>
 *
 * <p>它不负责什么：不做租户之间的隔离（跨租户隔离靠 tenantId，任何可见范围都不会越过租户边界），
 * 也不代表工具是否已发布可用。</p>
 */
public final class ToolVisibility {

    /** 私有工具：只有创建者本人（或租户管理员）能管理和调用；新建工具默认落到这一档，保证不会误把外部凭证暴露给同事。 */
    public static final String PRIVATE = "private";
    /** 租户公开工具：本租户所有成员都能在对话里调用，但管理动作（改配置、发新版本、停用）仍然只有 owner/admin 能做。 */
    public static final String TENANT_PUBLIC = "tenant_public";

    /** 私有构造：这个类只承载常量，不允许实例化，避免被误当成可注入的业务组件。 */
    private ToolVisibility() {
    }
}
