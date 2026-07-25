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

/** Agent 装配责任链基类；共享 Spring 运行时注册能力，不承载并行预加载。 */
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    /** 所有装配节点统一使用基类日志类别。 */
    protected final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    /** 用于读取插件 Bean，并将最终 Agent 运行体动态注册进容器。 */
    @Resource
    protected ApplicationContext applicationContext;

    /** 当前责任链节点不启用框架的并行预处理钩子。 */
    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }
    /** 以稳定名称原子替换单例定义；同步保护同一 JVM 内的并发重装配。 */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();

        // Bean 供应器返回已装配实例，Spring 不再自行调用构造器。
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

        // 重装配使用新定义完全替换旧运行体。
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }

        // 注册完成后，后续请求按 agentId 立即读取新单例。
        beanFactory.registerBeanDefinition(beanName, beanDefinition);

        log.info("成功注册Bean: {}", beanName);
    }

    /** 按配置名称读取已存在的插件或装配节点；类型由调用点约束。 */
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

}
