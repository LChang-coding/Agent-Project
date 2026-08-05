package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具类型的字面量常量：整个工具领域只认「Skill」和「MCP」两种工具。
 *
 * <p>所属层次：工具领域的值对象。这里不用枚举而用字符串常量，是因为数据库里存的就是这两个小写字符串，
 * 领域对象、仓储和前端传参一路都按字符串流转，避免每层来回做枚举转换。</p>
 *
 * <p>谁会用它：{@code ToolGateway} 用它决定把调用路由到 Skill 还是 MCP 运行时；
 * {@code GatewayAdkTool} 用它决定给大模型声明什么入参 schema、函数名加什么前缀；
 * {@code ToolPublishService} 用它区分两条发布生命周期。</p>
 *
 * <p>它不负责什么：不做取值合法性校验（校验在各调用点判等时自然完成），也不描述工具的状态和可见范围，
 * 那些分别在 {@code ToolStatus} 和 {@code ToolVisibility} 里。</p>
 */
public final class ToolType {

    /** 用 ZIP 包上传、内含 SKILL.md 指令文本的本地技能；调用它只是把指令文本读出来交给模型，不执行包内任何代码。 */
    public static final String SKILL = "skill";
    /** 走 MCP 协议连接到外部服务器的工具集；调用它会真的建连并执行远程动作，是真正会产生外部副作用的一类。 */
    public static final String MCP = "mcp";

    /** 私有构造：这个类只是一袋常量，不允许被 new 出实例，防止有人误当业务对象注入使用。 */
    private ToolType() {
    }
}
