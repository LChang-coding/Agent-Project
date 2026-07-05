package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具发布状态。
 */
public final class ToolStatus {

    public static final String DRAFT = "draft";
    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";
    public static final String FAILED = "failed";
    public static final String SUCCESS = "success";
    public static final String UNTESTED = "untested";

    /**
     * 禁止创建常量类；无参数；无返回值。
     */
    private ToolStatus() {
    }
}
