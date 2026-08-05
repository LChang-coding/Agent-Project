/**
 * Agent 领域的外部依赖出口层：领域层在这里只声明「我需要什么能力」，不关心怎么实现。
 *
 * <p>下面分两类：{@code repository} 放数据落库与读取的接口，{@code port} 放调用外部系统的接口。
 * 真正的实现都在基础设施层，由 Spring 在启动时注入进来。</p>
 *
 * <p>这样做的目的：领域层不会被 MyBatis、HTTP 客户端这些技术细节绑死，换存储或换下游时领域代码不用动。</p>
 */
package cn.bugstack.ai.domain.agent.adapter;
