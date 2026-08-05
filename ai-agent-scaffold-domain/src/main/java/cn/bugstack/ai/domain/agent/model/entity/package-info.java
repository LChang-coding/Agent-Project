/**
 * Agent 领域的实体对象：一次操作的输入命令，或一条可持久化记录在内存中的形态。
 *
 * <p>这里主要有两类：一类是命令实体（如 ChatCommandEntity、ArmoryCommandEntity），封装「这次要做什么」，
 * 由 trigger 层或领域服务组装后往下传；另一类是与库表对应的状态实体（如 AgentTenantOverrideEntity），
 * 通过 adapter 层的仓储接口读写。</p>
 *
 * <p>实体自身不查库、不调模型，只承载数据和极少量的构造便捷方法。</p>
 */
package cn.bugstack.ai.domain.agent.model.entity;
