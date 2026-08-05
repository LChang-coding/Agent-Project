package cn.bugstack.ai.domain.agent.service.armory.factory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.node.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 装配链的入口工厂，同时定义链上共享的「工作台」上下文。
 *
 * <p>解决什么问题：装配是分层的，后一层要用前一层的产物（模型要用 API 客户端，Agent 要用模型）。
 * 需要一个只在本次装配期间存在的容器来传递这些中间产物，这就是内部类 DynamicContext。
 * 另外对话时要能按 agentId 找到装配好的运行体，这个查找入口也放在这里。</p>
 *
 * <p>所属层次：领域层的装配工厂。</p>
 *
 * <p>谁会调用它：{@code ArmoryService} 取链头并新建上下文；{@code ChatService} 按 agentId 取运行体。</p>
 *
 * <p>它不负责什么：自己不装配任何东西、不缓存运行体（缓存由 Spring 容器承担）、
 * 也不校验配置合法性。</p>
 */
@Service
public class DefaultArmoryFactory {

    /**
     * Spring 容器，同时充当「已装配 Agent 运行体」的运行时注册表。
     *
     * <p>装配完成的运行体以 agentId 为 Bean 名注册在这里，因此按 agentId 取 Bean 就等于查注册表，
     * 不需要额外维护一份 Map。取不到说明该 Agent 没装配成功或配置里根本没有它。</p>
     */
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 装配责任链的固定起点。
     *
     * <p>所有装配都从它开始，由它把请求转给第一个真正干活的节点（API 客户端节点）。
     * 起点固定的好处是装配顺序在代码里一目了然，不会因为调用方不同而走出不同路径。</p>
     */
    @Resource
    private RootNode rootNode;

    /**
     * 取出装配责任链的入口节点。
     *
     * <p>链上的节点本身是无状态的 Spring 单例，可以被并发复用；所有可变的中间产物都放在
     * 调用方传入的 DynamicContext 里。因此每次装配必须传一份新的上下文，
     * 复用同一份会让不同配置表的模型和 Agent 相互串用。</p>
     */
    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        // 返回链头；真正的执行由调用方 apply 触发。
        return rootNode;
    }

    /**
     * 按 agentId 取出已经装配完成并注册进容器的运行体。
     *
     * <p>这是对话链路的第一步：拿不到运行体就说明启动装配没做或配置无效，
     * 此时会抛出 Spring 的 Bean 未找到异常，由上层翻译成「智能体不可用」。</p>
     */
    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        // 用 agentId 当 Bean 名取运行体，并按类型校验，防止取到同名的其它 Bean。
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    /**
     * 一次装配过程中的共享工作台，只在单张配置表的责任链执行期间有效。
     *
     * <p>解决什么问题：装配链上的节点之间需要传递中间产物，但又不希望把这些产物暴露成全局状态。
     * 这个对象随一次装配创建、随装配结束丢弃，天然做到了「不同配置表互不干扰」。</p>
     *
     * <p>谁会读写它：链上每个装配节点。前面的节点写入，后面的节点读取。</p>
     *
     * <p>它不负责什么：不是缓存、不是注册表，装配结束后就该被丢掉；
     * 想让产物长期可用必须显式注册进 Spring 容器。</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * API 节点造出的底层 HTTP 客户端，承载模型服务地址、密钥和路径。
         *
         * <p>后面所有模型（默认模型和 Agent 私有模型）都复用同一个客户端，
         * 这样连接池只有一份，不会因为多个 Agent 各建一套而把连接数打满。</p>
         */
        private OpenAiApi openAiApi;

        /**
         * 这张配置表的默认聊天模型实例。
         *
         * <p>没有显式指定模型的原子 Agent 直接复用它；最终它还会被放进运行体，
         * 供上下文压缩使用。为空说明模型节点没跑成功，后续 Agent 装配一定失败。</p>
         */
        private ChatModel chatModel;

        /**
         * 默认模型的模型代码字符串。
         *
         * <p>两处用途：一是 Agent 没写模型时用它作为回退值，二是可观测性打点要按模型维度统计耗时和用量。
         * 它只是个标识，改它不会改变实际调用的模型实例。</p>
         */
        private String chatModelName;

        /**
         * 历史遗留的工具回调列表，装配过程中会被显式清空并保持为空。
         *
         * <p>为什么留着却不用：现在所有工具调用统一走网关工具集（GatewayToolset），
         * 那里才有运行取消门禁和身份校验。如果这里挂上工具，模型就能绕过门禁直接调工具，
         * 取消运行后仍可能产生工具消费。保留字段只为兼容旧配置结构。</p>
         */
        @Builder.Default
        private List<ToolCallback> toolCallbacks = new ArrayList<>();

        /**
         * 本次装配已经建好的 Agent 名称索引，原子 Agent 和组合 Agent 都放在这里。
         *
         * <p>它是配置文件里那些名字字符串能被解析成真实对象的唯一依据：工作流的 subAgents
         * 和 Runner 的 agentName 都从这里按名查找。因为装配严格自下而上，被引用的名字必须先建好，
         * 引用了尚未装配的名字会静默拿不到，表现为工作流少了子 Agent。</p>
         */
        @Builder.Default
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        /**
         * 当前正在装配第几条组合工作流配置。
         *
         * <p>组合工作流是「调度节点 → 具体类型节点 → 回到调度节点」这样绕圈装配的，
         * 靠这个下标记住进度；下标追上配置数量就表示全部装完，可以去建 Runner 了。
         * 用原子类型只是沿用框架惯例，实际装配是单线程的。</p>
         */
        @Builder.Default
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        /**
         * 本轮正在处理的那条组合工作流配置。
         *
         * <p>置为空是一个明确的哨兵：调度节点用它告诉路由「组合配置已经全部消费完」，
         * 于是路由转向 Runner 节点结束装配。</p>
         */
        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        /**
         * 少量跨节点传递的扩展数据，键由写入方和读取方自行约定。
         *
         * <p>用于放不值得单独开字段的临时值。因为没有类型约束，键写错只会读出空值而不会报错，
         * 所以只适合放非关键信息，重要产物应该有自己的字段。</p>
         */
        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        /**
         * 往扩展数据里写一个值。
         *
         * <p>同名键会被直接覆盖，调用方需自己保证键不冲突。</p>
         */
        public <T> void setValue(String key, T value) {
            // 直接放入扩展表，供链路上后续节点按同一个键读取。
            dataObjects.put(key, value);
        }

        /**
         * 从扩展数据里读一个值。
         *
         * <p>做了未检查的强制转换，类型必须和写入时一致；键不存在时返回空值，
         * 调用方要自己处理空的情况。</p>
         */
        public <T> T getValue(String key) {
            // 按键取出并强转成调用点期望的类型；类型不符会在使用处抛类型转换异常。
            return (T) dataObjects.get(key);
        }

        /**
         * 把配置里的一串子 Agent 名字解析成已装配好的 Agent 实例列表。
         *
         * <p>各层职责：
         * 第一层：入参或索引为空时直接返回空列表，让调用方拿到一个可安全遍历的结果。
         * 第二层：按声明顺序逐个查名字，顺序必须保留——顺序型工作流就是靠它决定执行次序的。
         * 第三层：查不到的名字直接跳过，不放空占位，避免把空对象带进 ADK 导致执行时才崩。</p>
         *
         * <p>数据流：子 Agent 名字列表 → 逐个在名称索引里查找 → 命中则按序加入结果 → 返回实例列表。</p>
         *
         * <p>注意这里对「引用了不存在的名字」是宽容的：结果会静默变少。
         * 表现出来是工作流少跑了一个环节，而不是明确报错，配置写错时需要留意这一点。</p>
         */
        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            // 第一层：没有要解析的名字，或者索引根本还没建起来，返回空列表让调用方照常构造工作流。
            if (agentNames == null || agentNames.isEmpty() || agentGroup == null) {
                // 用不可变空列表，避免调用方误以为可以往里追加。
                return Collections.emptyList();
            }

            // 准备结果容器，按声明顺序装入解析出的实例。
            List<BaseAgent> agents = new ArrayList<>();
            // 第二层：严格按配置声明顺序遍历，顺序决定串行工作流的执行次序。
            for (String name : agentNames) {
                // 在本次装配的名称索引里查这个名字对应的实例。
                BaseAgent agent = agentGroup.get(name);
                // 第三层：只有真的查到才加入；查不到就跳过，不用空值占位。
                if (agent != null) {
                    // 按顺序追加，保证结果顺序和配置顺序一致。
                    agents.add(agent);
                }
            }

            // 返回解析结果；长度可能小于入参长度，说明有名字没对应上。
            return agents;
        }

        /**
         * 把组合工作流的装配进度推进一条。
         *
         * <p>由调度节点在取出当前配置后立即调用，这样具体类型节点装配完回到调度节点时，
         * 读到的就是下一条配置，不会陷入反复装配同一条的死循环。</p>
         */
        public void addCurrentStepIndex() {
            // 进度加一，下一轮回环时读取下一条组合配置。
            currentStepIndex.incrementAndGet();
        }

        /**
         * 读取当前组合工作流的装配进度。
         *
         * <p>调度节点用它和配置总数比较，判断是否已经全部装完。</p>
         */
        public int getCurrentStepIndex() {
            // 取出当前进度值。
            return currentStepIndex.get();
        }

    }

}
