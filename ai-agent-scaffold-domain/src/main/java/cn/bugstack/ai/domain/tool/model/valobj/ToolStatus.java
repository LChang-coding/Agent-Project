package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具发布状态。
 */
public final class ToolStatus {

    /** 尚未发布。 */
    public static final String DRAFT = "draft";
    /** 已发布且可调用。 */
    public static final String ACTIVE = "active";
    /** 已停用。 */
    public static final String DISABLED = "disabled";
    /** 调用已取得执行权。 */
    public static final String STARTED = "started";
    /** 测试或调用失败。 */
    public static final String FAILED = "failed";
    /** 测试或调用成功。 */
    public static final String SUCCESS = "success";
    /** 尚未执行连接测试。 */
    public static final String UNTESTED = "untested";

    /** 禁止实例化常量类。 */
    private ToolStatus() {
    }
}
