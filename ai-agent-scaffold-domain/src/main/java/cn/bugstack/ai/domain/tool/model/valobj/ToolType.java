package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具目录和分发逻辑共同使用的类型常量。
 *
 * <p>所属层次：工具领域的值对象。这里不用枚举而用字符串常量，是因为数据库里存的就是这两个小写字符串，
 * 领域对象、仓储和前端传参一路都按字符串流转，避免每层来回做枚举转换。</p>
 *
 * <p>{@code ToolGateway} 用它决定把调用分发到 Skill、MCP 或平台内置处理器；
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
    /** 仅由服务端按运行上下文生成并在进程内执行的内置工具。 */
    public static final String PLATFORM = "platform";

    /** 常量类不允许实例化。 */
    private ToolType() {
    }
}
