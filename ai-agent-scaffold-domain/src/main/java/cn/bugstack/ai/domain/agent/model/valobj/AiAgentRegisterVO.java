package cn.bugstack.ai.domain.agent.model.valobj;

import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * 一个 Agent 装配完成后的运行时句柄：身份信息 + 可以直接执行的 ADK Runner。
 *
 * <p>解决什么问题：装配（读配置、建模型、连工具、组Agent）很重，不可能每次对话都做一遍。
 * 装配链跑完后把成果打包成这个对象放进 Spring 容器，对话时按 agentId 取出来直接用。</p>
 *
 * <p>所属层次：领域层的值对象，生命周期跟随应用进程，不落库。</p>
 *
 * <p>谁会调用它：{@code ChatService} 在建会话和发消息时按 agentId 取出它，用 runner 跑对话、
 * 用 chatModel 做上下文压缩；Agent 列表接口则只读它的 agentId/名称/描述。</p>
 *
 * <p>它不负责什么：不保存会话和消息（那些在数据库里），不做租户级启停判断（那在可用性服务里），
 * 也不负责在配置变更后自我刷新——配置改了必须重新装配才会生效。</p>
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /**
     * ADK Runner 的应用名，用来在 ADK 内部区分不同应用的会话空间。
     *
     * <p>它和平台自己的 sessionId 不是一回事：平台 sessionId 用于落库和权限校验，
     * appName 只在 ADK 侧定位会话。两边对不上会导致模型读不到历史上下文。</p>
     */
    private String appName;

    /**
     * 对外的 Agent 编号，前端建会话和发消息时携带的就是它。
     *
     * <p>它同时是运行时注册表的查询键：取不到对应句柄就说明这个 Agent 没装配成功，
     * 对话会直接失败而不是降级成默认模型。</p>
     */
    private String agentId;

    /** 展示给用户的 Agent 名称，来自配置；只用于列表和日志，不参与执行逻辑。 */
    private String agentName;

    /** Agent 的能力描述，来自配置；帮用户在下拉框里选对智能体，不参与执行逻辑。 */
    private String agentDesc;

    /** 服务端配置的编排角色。 */
    private String orchestrationRole;

    /** 主 Agent 可创建的子 Agent 白名单。 */
    private List<String> allowedSubAgentIds;

    /**
     * 已经把模型、工具、子 Agent 全部装好的 ADK 执行器，是真正跑对话的东西。
     *
     * <p>它内部持有 MCP 客户端连接等重资源，因此整个进程共享同一个实例，
     * 多个用户的请求会并发进入同一个 Runner，会话之间靠 ADK 的 sessionId 隔离。</p>
     */
    private Runner runner;

    /**
     * 这个 Agent 使用的 Spring AI 聊天模型。
     *
     * <p>除了对话本身，上下文压缩（把过长的历史总结成一段话）也复用它，
     * 这样压缩用的模型和回答用的模型口径一致，不会出现「总结得和回答不是一个风格」。
     * 为空时上下文压缩无法进行，超长会话只能靠截断处理。</p>
     */
    private ChatModel chatModel;

}
