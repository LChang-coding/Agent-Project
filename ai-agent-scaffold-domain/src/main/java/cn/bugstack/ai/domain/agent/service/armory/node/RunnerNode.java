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
 * 装配链的终点：把根 Agent 和插件包成 Runner，并以 agentId 为名把运行体注册进容器。
 *
 * <p>解决什么问题：前面几层把零件都造好了，这里负责组装成成品并「上架」。
 * 上架之后 {@code ChatService} 才能按 agentId 取到它并真正跑对话。</p>
 *
 * <p>所属层次：领域层的装配节点（责任链终点）。</p>
 *
 * <p>谁会调用它：{@code AgentWorkflowNode} 在组合配置消费完后转给它。</p>
 *
 * <p>它向下调用什么：从共享上下文取根 Agent，从 Spring 容器按名取显式插件，
 * 并强制补齐两个安全相关的插件（上下文注入、工具执行门禁），最后调用基类的注册能力上架运行体。</p>
 *
 * <p>它不负责什么：不校验根 Agent 是否真的存在于索引里（取不到会在 Runner 构造时暴露）、
 * 不做任何对话执行。</p>
 */
@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    /**
     * 上下文注入插件的延迟获取器；这个插件负责在每次模型调用前把历史消息、附件和 RAG 检索结果装进去。
     *
     * <p>用延迟获取而不是直接注入，是因为插件的实现在基础设施层。装配层如果硬依赖它，
     * 就会形成领域层反向依赖基础设施层；而且插件缺失时也不该让整个装配挂掉。</p>
     */
    private final ObjectProvider<ContextInjectionPlugin> contextInjectionPluginProvider;
    /**
     * 工具执行门禁插件的延迟获取器；它在每次工具调用前检查运行是否已被取消。
     *
     * <p>只要它存在就必须挂上，否则用户点了停止之后，模型仍能继续触发工具调用，
     * 产生本不该发生的外部副作用和费用。</p>
     */
    private final ObjectProvider<ToolExecutionGuardPlugin> toolExecutionGuardPluginProvider;

    /**
     * 注入两个插件的延迟获取器。
     *
     * <p>用 ObjectProvider 而不是插件本身，保证构造这个节点时不会触发插件初始化，
     * 也不要求插件一定存在，避免装配层被基础设施的加载顺序绑住。</p>
     */
    public RunnerNode(ObjectProvider<ContextInjectionPlugin> contextInjectionPluginProvider,
                      ObjectProvider<ToolExecutionGuardPlugin> toolExecutionGuardPluginProvider) {
        // 保存上下文注入插件的获取器，建 Runner 时才真正去取。
        this.contextInjectionPluginProvider = contextInjectionPluginProvider;
        // 保存工具门禁插件的获取器，建 Runner 时才真正去取。
        this.toolExecutionGuardPluginProvider = toolExecutionGuardPluginProvider;
    }

    /**
     * 组装 Runner 和运行体，并把运行体按 agentId 注册进 Spring 容器。
     *
     * <p>各层职责：
     * 第一层：从配置表取出对外身份信息（应用名、agentId、名称、描述），它们决定这个运行体怎么被找到和展示。
     * 第二层：构造 Runner——解析根 Agent、解析插件、补齐必需插件。
     * 第三层：把身份、Runner 和默认模型打包成运行体；模型要一起带上，因为上下文压缩会复用它。
     * 第四层：以 agentId 为 Bean 名注册进容器，重新装配时会整体替换旧的运行体。</p>
     *
     * <p>数据流：
     * 配置表
     * → 取出 appName / agentId / 名称 / 描述
     * → 解析根 Agent 与插件列表
     * → 构造 InMemoryRunner
     * → 打包成 AiAgentRegisterVO（含默认模型）
     * → 按 agentId 注册进 Spring 容器
     * → 返回运行体给调用方</p>
     *
     * <p>不写数据库。注册成功后对话请求立刻能取到新运行体；注册失败则该 Agent 整体不可用。</p>
     */
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 打点标明进入装配终点环节。
        log.info("Ai Agent 装配操作 - RunnerNode");

        // 第一层：取出整棵配置树。
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        // 取出 ADK 应用名，它决定模型侧会话数据落在哪个应用空间。
        String appName = aiAgentConfigTableVO.getAppName();
        // 取出对外暴露的那个 Agent 的身份段。
        AiAgentConfigTableVO.Agent agent = aiAgentConfigTableVO.getAgent();
        // agentId 是注册键，也是前端和 ChatService 寻址的唯一依据。
        String agentId = agent.getAgentId();
        // 展示名称，用于界面和会话标题。
        String agentName = agent.getAgentName();
        // 能力描述，供用户在列表里判断该选哪个智能体。
        String agentDesc = agent.getAgentDesc();

        // 第二层：Runner 配置只引用已经放入 agentGroup 的根 Agent。
        InMemoryRunner runner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);

        // 第三层：把身份、Runner 和默认模型打包成运行时句柄；模型要带上，上下文压缩会复用它。
        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .chatModel(dynamicContext.getChatModel())
                .build();

        // 第四层：agentId 是 ChatService 查找运行体的稳定注册键；重装配会替换旧单例。
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        // 返回运行体，沿责任链调用栈交回给最初的调用方。
        return aiAgentRegisterVO;
    }

    /**
     * 解析出根 Agent 和插件列表，构造真正执行对话的 Runner。
     *
     * <p>各层职责：
     * 第一层：校验配置里指定了根 Agent 名，没指定就直接拒绝——没有根 Agent 的 Runner 无法执行任何事。
     * 第二层：按名字从本次装配的索引里取根 Agent。
     * 第三层：按配置顺序从容器里逐个取显式插件；没配插件就用空列表。
     * 第四层：无论配没配，都强制补齐上下文注入和工具门禁两个插件，保证安全能力不会因为漏配而缺失。</p>
     *
     * <p>数据流：
     * Runner 配置
     * → 校验根 Agent 名非空
     * → 从名称索引取根 Agent
     * → 按名解析显式插件列表
     * → 补齐上下文注入插件
     * → 补齐工具执行门禁插件
     * → 构造 InMemoryRunner</p>
     *
     * <p>根 Agent 名字写错时索引里取不到，这里不会报错，而是把空值交给 Runner 构造，
     * 问题会推迟到对话时才暴露——这是一个已知的薄弱点。</p>
     */
    @NotNull
    private  InMemoryRunner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        // 取出 Runner 段配置：根 Agent 名和插件名列表。
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        // 第一层：根 Agent 名是必填项。
        String agentName = runnerConfig.getAgentName();
        // 没填就没法确定该跑谁，直接拒绝装配。
        if (StringUtils.isBlank(agentName)) {
            // 先记错误日志，便于从启动日志定位是哪张配置表写漏了。
            log.error("runner.agentName is null");
            // 抛参数非法异常中断装配，避免注册出一个跑不起来的运行体。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        // 第二层：agentName 必须与原子或组合 Agent 的配置名称一致，否则这里取不到。
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

        // 第三层：准备插件容器；显式插件按配置顺序从 Spring 容器解析。
        List<BasePlugin> plugins;
        // 取出配置里声明的插件名列表。
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        // 配了插件就逐个按名解析，顺序即生效顺序。
        if (null != pluginNameList && !pluginNameList.isEmpty()) {
            // 新建列表承载解析结果。
            plugins = new ArrayList<>();
            // 按配置顺序逐个解析，名字写错会在这里抛 Bean 未找到异常。
            for (String pluginName : pluginNameList) {
                // 从容器里按名取出插件实例。
                BasePlugin plugin = getBean(pluginName);
                // 按顺序加入，保证插件生效顺序和配置一致。
                plugins.add(plugin);
            }
        } else {
            // 没配插件也要给一个可写的空列表，下面还要往里补必需插件。
            plugins = new ArrayList<>();
        }
        // 第四层：强制补齐上下文注入插件，缺了它模型看不到历史消息和检索结果。
        appendContextPlugin(plugins);
        // 强制补齐工具门禁插件，缺了它取消运行后工具还会继续执行。
        appendToolExecutionGuardPlugin(plugins);

        // 用根 Agent、应用名和插件列表构造 Runner；它是整个对话链路真正的执行者。
        return new InMemoryRunner(baseAgent, appName, plugins);
    }

    /**
     * 把上下文注入插件补进插件列表，已存在则不重复添加。
     *
     * <p>为什么要补：这个插件负责在每次模型调用前装配历史消息、附件和 RAG 检索结果。
     * 配置里漏写它，对话就会变成「每轮都不记得前面说过什么」，问题不明显但影响很大。</p>
     *
     * <p>按插件名去重而不是按对象去重：配置里可能已经显式写了它，重复挂载会导致上下文被注入两遍。</p>
     *
     * <p>插件在容器里不存在时直接跳过，不抛异常——允许在不需要上下文能力的精简部署里缺省它。</p>
     */
    private void appendContextPlugin(List<BasePlugin> plugins) {
        // 尝试从容器取插件；取不到说明当前部署没有这个能力。
        ContextInjectionPlugin contextInjectionPlugin = contextInjectionPluginProvider.getIfAvailable();
        // 不存在就什么都不做，Runner 照常构造。
        if (contextInjectionPlugin == null) {
            // 直接返回，插件列表保持原样。
            return;
        }
        // 按插件名判断是否已经在列表里，避免配置里已显式声明时被挂两遍。
        boolean exists = plugins.stream()
                .anyMatch(plugin -> plugin != null && contextInjectionPlugin.getName().equals(plugin.getName()));
        // 确实还没有才追加。
        if (!exists) {
            // 追加到列表末尾，让它在显式插件之后生效。
            plugins.add(contextInjectionPlugin);
        }
    }

    /**
     * 把工具执行门禁插件补进插件列表，已存在则不重复添加。
     *
     * <p>为什么要补：它是「取消运行」能真正生效的最后一道闸门。每次工具调用前它都会去查权威运行状态，
     * 已取消就拒绝执行。漏挂它的后果是用户点了停止，但工具仍在往外部系统写数据。</p>
     *
     * <p>同样按插件名去重，避免重复校验带来多余的状态查询。</p>
     */
    private void appendToolExecutionGuardPlugin(List<BasePlugin> plugins) {
        // 尝试从容器取门禁插件。
        ToolExecutionGuardPlugin plugin = toolExecutionGuardPluginProvider.getIfAvailable();
        // 不存在就跳过，不阻断装配。
        if (plugin == null) {
            // 直接返回，插件列表保持原样。
            return;
        }
        // 按插件名判断是否已在列表里。
        boolean exists = plugins.stream()
                .anyMatch(item -> item != null && plugin.getName().equals(item.getName()));
        // 还没有才追加。
        if (!exists) {
            // 追加到末尾，保证工具调用前一定会过这道门禁。
            plugins.add(plugin);
        }
    }

    /**
     * 声明本节点是责任链终点。
     *
     * <p>返回框架的默认处理器，意思是「不再往下走」，于是 doApply 的返回值会沿调用栈交回最初的调用方。</p>
     */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // Runner 是责任链终点，doApply 的返回值直接交给调用者。
        return defaultStrategyHandler;
    }


}
