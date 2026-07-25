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

/** 开发期 ADK 生命周期探针；仅记录用户输入和 Agent 名称。 */
@Slf4j
@Service("myTestPlugin")
public class MyTestPlugin extends BasePlugin {

    /** 允许测试以自定义插件名实例化。 */
    public MyTestPlugin(String name) {
        super(name);
    }

    /** Spring 默认实例使用固定名称。 */
    public MyTestPlugin() {
        super("MyTestPlugin");
    }

    /** 用户消息进入 Agent 前记录文本；不修改消息。 */
    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        log.info("用户输入信息:{}", userMessage.text());
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    /** Agent 执行前记录名称；不短路 Agent。 */
    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        String agentName = agent.name();
        log.info("智能体名称:{}", agentName);
        return super.beforeAgentCallback(agent, callbackContext);
    }



}
