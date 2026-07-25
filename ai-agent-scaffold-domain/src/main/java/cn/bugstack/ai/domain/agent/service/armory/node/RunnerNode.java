package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.plugin.ContextInjectionPlugin;
import cn.bugstack.ai.domain.agent.service.armory.matter.plugin.ToolExecutionGuardPlugin;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 将选定根 Agent 与必要插件封装为 Runner，并按 agentId 发布运行体。 */
@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    /** 延迟读取可选上下文插件，避免装配层强依赖其基础设施实现。 */
    private final ObjectProvider<ContextInjectionPlugin> contextInjectionPluginProvider;
    /** 延迟读取工具执行守卫；存在时必须自动附加。 */
    private final ObjectProvider<ToolExecutionGuardPlugin> toolExecutionGuardPluginProvider;

    /** 注入可选插件提供器，不在构造阶段触发插件初始化。 */
    public RunnerNode(ObjectProvider<ContextInjectionPlugin> contextInjectionPluginProvider,
                      ObjectProvider<ToolExecutionGuardPlugin> toolExecutionGuardPluginProvider) {
        this.contextInjectionPluginProvider = contextInjectionPluginProvider;
        this.toolExecutionGuardPluginProvider = toolExecutionGuardPluginProvider;
    }

    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();
        AiAgentConfigTableVO.Agent agent = aiAgentConfigTableVO.getAgent();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();

        // Runner 配置只引用已经放入 agentGroup 的根 Agent。
        InMemoryRunner runner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .chatModel(dynamicContext.getChatModel())
                .build();

        // agentId 是 ChatService 查找运行体的稳定注册键；重装配会替换旧单例。
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    @NotNull
    /** 解析根 Agent 和显式插件，并强制补齐上下文与工具安全插件。 */
    private  InMemoryRunner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        // agentName 必须与原子或组合 Agent 的配置名称一致。
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

        // 显式插件按配置顺序从 Spring 容器解析。
        List<BasePlugin> plugins;
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        if (null != pluginNameList && !pluginNameList.isEmpty()) {
            plugins = new ArrayList<>();
            for (String pluginName : pluginNameList) {
                BasePlugin plugin = getBean(pluginName);
                plugins.add(plugin);
            }
        } else {
            plugins = new ArrayList<>();
        }
        appendContextPlugin(plugins);
        appendToolExecutionGuardPlugin(plugins);

        return new InMemoryRunner(baseAgent, appName, plugins);
    }

    /** 自动补齐上下文注入插件，并按插件名去重。 */
    private void appendContextPlugin(List<BasePlugin> plugins) {
        ContextInjectionPlugin contextInjectionPlugin = contextInjectionPluginProvider.getIfAvailable();
        if (contextInjectionPlugin == null) {
            return;
        }
        boolean exists = plugins.stream()
                .anyMatch(plugin -> plugin != null && contextInjectionPlugin.getName().equals(plugin.getName()));
        if (!exists) {
            plugins.add(contextInjectionPlugin);
        }
    }

    /** 自动补齐工具前置取消门禁，并按插件名去重。 */
    private void appendToolExecutionGuardPlugin(List<BasePlugin> plugins) {
        ToolExecutionGuardPlugin plugin = toolExecutionGuardPluginProvider.getIfAvailable();
        if (plugin == null) {
            return;
        }
        boolean exists = plugins.stream()
                .anyMatch(item -> item != null && plugin.getName().equals(item.getName()));
        if (!exists) {
            plugins.add(plugin);
        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // Runner 是责任链终点，doApply 的返回值直接交给调用者。
        return defaultStrategyHandler;
    }


}
