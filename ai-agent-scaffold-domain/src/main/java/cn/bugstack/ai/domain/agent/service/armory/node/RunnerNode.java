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

/**
 * 执行节点
 *
 * 2025/12/29 16:09
 */
@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    private final ObjectProvider<ContextInjectionPlugin> contextInjectionPluginProvider;
    private final ObjectProvider<ToolExecutionGuardPlugin> toolExecutionGuardPluginProvider;

    /**
     * 创建 Runner 装配节点；参数是上下文插件延迟提供器；返回节点实例。
     */
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

        InMemoryRunner runner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .chatModel(dynamicContext.getChatModel())
                .build();

        // 注册到 Spring 容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    @NotNull
    private  InMemoryRunner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

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

    /**
     * 自动附加上下文插件；参数是插件列表；无返回值。
     */
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

    /**
     * 自动附加工具执行守卫插件；参数是插件列表；无返回值。
     */
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
        return defaultStrategyHandler;
    }


}
