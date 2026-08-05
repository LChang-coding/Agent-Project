package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;

import javax.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 所有装配节点的公共基类：提供日志、按名取 Bean 和运行期注册 Bean 三样能力。
 *
 * <p>解决什么问题：装配链上的每个节点都要做两件相同的事——从容器里按配置名取出插件或下一个节点，
 * 以及把自己造出来的对象塞回容器供后续使用。把这两件事收在基类里，节点自身只关心「怎么造对象」。</p>
 *
 * <p>所属层次：领域层的装配支撑类（抽象基类），继承自责任链框架的多线程路由基类。</p>
 *
 * <p>谁会继承它：RootNode、AiApiNode、ChatModelNode、AgentNode、AgentWorkflowNode、RunnerNode
 * 以及三个组合工作流节点。</p>
 *
 * <p>它不负责什么：不决定装配顺序（顺序由各节点的 get() 指定）、不校验配置、不并行预加载。</p>
 */
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    /**
     * 全部装配节点共用的日志器，类别固定为本基类。
     *
     * <p>好处是装配日志天然聚在一起，排查启动问题时按一个类别就能拉出完整装配过程；
     * 代价是日志里看不出具体是哪个子类打的，所以各节点在日志正文里自己带上节点名。</p>
     */
    protected final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    /**
     * Spring 容器句柄，装配节点靠它读插件、读下一个节点，并把装配好的运行体注册进去。
     *
     * <p>它是运行时 Agent 注册表的真身：{@code ChatService} 后续按 agentId 从同一个容器里取运行体。
     * 因此这里注册进去的东西是全局共享的单例，会被所有租户的请求并发访问。</p>
     */
    @Resource
    protected ApplicationContext applicationContext;

    /**
     * 责任链框架提供的并行预处理钩子，本装配链刻意留空。
     *
     * <p>为什么不用：装配是严格有依赖顺序的（没有 API 客户端就建不出模型，没有模型就建不出 Agent），
     * 并行预加载在这里没有收益，反而会引入难以排查的时序问题。</p>
     */
    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }
    /**
     * 用固定名称把一个已经装配好的实例注册成容器里的单例，已存在则整体替换。
     *
     * <p>各层职责：
     * 第一层：拿到可注册 Bean 定义的 BeanFactory，这是运行期动态注册的前提。
     * 第二层：用「供应器」方式定义 Bean，让 Spring 直接使用现成实例而不是再走一遍构造流程。
     * 第三层：同名定义先删再注册，保证重新装配时旧运行体被彻底替换而不是留下两份。</p>
     *
     * <p>数据流：Bean 名 + 类型 + 已装配实例 → 构造单例 Bean 定义 → 删除同名旧定义 → 注册新定义
     * → 后续按名取用立即拿到新实例。</p>
     *
     * <p>方法加了同步：同一个 JVM 内如果有两次重装配同时进行，「先删后注册」这两步之间存在一个
     * 名称不存在的窗口，此时别的请求按名取 Bean 会失败。加锁把这个窗口串行化，缩小影响面。
     * 注意它只保护本进程，多实例部署时各实例各自装配，互不影响。</p>
     */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        // 第一层：取出支持运行期增删 Bean 定义的工厂；普通 ApplicationContext 接口不提供这个能力。
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();

        // 第二层：用现成实例作为 Bean 供应器，Spring 不再自行调用构造器，
        // 这样装配过程中建立的连接、线程池等重资源都能原样保留。
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        // 取出原始定义准备设置作用域。
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        // 明确声明为单例：全进程共用一份运行体，避免每次取用都造一个新的 Runner。
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

        // 第三层：同名定义已存在说明这是一次重新装配，先移除旧的，
        // 否则注册会失败或留下旧运行体继续被使用。
        if (beanFactory.containsBeanDefinition(beanName)) {
            // 移除旧定义，连带失效旧单例。
            beanFactory.removeBeanDefinition(beanName);
        }

        // 注册新定义；这一步完成后，后续请求按 agentId 取到的就是新运行体。
        beanFactory.registerBeanDefinition(beanName, beanDefinition);

        // 留下注册成功的痕迹，启动或热更新排查时用它确认某个 Agent 到底有没有装配上。
        log.info("成功注册Bean: {}", beanName);
    }

    /**
     * 按名字从容器里取出插件或另一个装配节点。
     *
     * <p>为什么用名字而不是类型：插件和组合工作流节点是在 YAML 里用字符串指定的，
     * 编译期不知道具体类型，只能按名解析。</p>
     *
     * <p>返回值做了未检查的强制转换，类型正确性由调用点保证；名字写错时 Spring 会直接抛异常，
     * 装配随之失败，这比返回 null 让问题延后暴露更安全。</p>
     */
    protected <T> T getBean(String beanName) {
        // 按名取 Bean 并强转成调用点期望的类型；名字不存在会抛异常中断装配。
        return (T) applicationContext.getBean(beanName);
    }

}
