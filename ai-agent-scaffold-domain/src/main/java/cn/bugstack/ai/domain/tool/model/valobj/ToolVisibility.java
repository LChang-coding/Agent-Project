package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具可见范围。
 */
public final class ToolVisibility {

    /** 仅所有者可管理和调用。 */
    public static final String PRIVATE = "private";
    /** 当前租户成员可调用，仍只有授权角色可管理。 */
    public static final String TENANT_PUBLIC = "tenant_public";

    /** 禁止实例化常量类。 */
    private ToolVisibility() {
    }
}
