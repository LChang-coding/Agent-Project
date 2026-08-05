/**
 * 外部系统调用出口：领域层需要访问本系统之外的服务时，在这里定义接口。
 *
 * <p>接口由基础设施层的 adapter 具体实现（HTTP、RPC、SDK 等），领域层只依赖接口，
 * 因此外部服务地址、协议、重试策略的变化都不会传导到领域逻辑里。</p>
 */
package cn.bugstack.ai.domain.agent.adapter.port;
