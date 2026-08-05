package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 开发调试用的 ADK 生命周期探针，只打印用户输入和当前 Agent 名。
 *
 * <p>解决什么问题：排查「消息到底进没进 Agent」「路由到了哪个 Agent」这类问题时，
 * 需要一个最简单的观察点。它不改变任何行为，挂上去只多两行日志。</p>
 *
 * <p>所属层次：领域层的装配辅料（ADK 插件）。</p>
 *
 * <p>谁会调用它：ADK 在执行过程中按生命周期回调它，前提是配置里显式把它挂进了 Runner 插件列表。</p>
 *
 * <p>它不负责什么：不做任何拦截、不修改消息、不写库。</p>
 *
 * <p>注意它会把用户原始输入打进日志，涉及个人信息或敏感内容时不适合在生产环境启用。</p>
 */
@Slf4j
@Service("myTestPlugin")
public class MyTestPlugin extends BasePlugin {

    /**
     * 用自定义名字创建插件实例。
     *
     * <p>插件名是 Runner 去重的依据，测试里需要挂多个同类插件时用这个构造方法区分。</p>
     */
    public MyTestPlugin(String name) {
        // 交给父类记下插件名，Runner 按它识别和去重。
        super(name);
    }

    /**
     * Spring 创建默认实例时使用的构造方法，插件名固定。
     *
     * <p>名字写死是为了让配置里引用它时有一个稳定的标识。</p>
     */
    public MyTestPlugin() {
        // 用固定名字注册，配置里按这个名字引用。
        super("MyTestPlugin");
    }

    /**
     * 用户消息进入 Agent 之前打印一次文本内容。
     *
     * <p>只观察不改写：把消息原样交回父类默认实现，因此挂上它不会改变对话行为。</p>
     */
    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        // 打印用户这轮说了什么，用于确认消息确实进到了 Agent。
        log.info("用户输入信息:{}", userMessage.text());
        // 原样交回默认实现，不短路也不改写消息。
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    /**
     * Agent 开始执行之前打印一次 Agent 名。
     *
     * <p>在组合工作流里特别有用：能直接看出实际执行的是哪个子 Agent，
     * 确认编排和配置是否一致。</p>
     *
     * <p>同样只观察不短路，返回父类默认结果。</p>
     */
    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        // 取出即将执行的 Agent 名。
        String agentName = agent.name();
        // 打印出来，用于确认编排路由是否符合预期。
        log.info("智能体名称:{}", agentName);
        // 返回父类默认结果，不干预 Agent 执行。
        return super.beforeAgentCallback(agent, callbackContext);
    }



}
