/**
 * Agent 领域的值对象：只承载数据形状，没有业务行为。
 *
 * <p>主要放两类东西：一类是从 YAML 或数据库反序列化出来的多层 Agent 配置树（如 AiAgentConfigTableVO），
 * 装配链会顺着它一层层建出模型、工具、Agent 和 Runner；另一类是装配完成后登记到内存里的运行时句柄
 * （如 AiAgentRegisterVO），对话时凭 agentId 直接取用。</p>
 *
 * <p>它不负责什么：不做校验、不查库、不判断状态，判空和合法性检查都在领域服务里做。</p>
 */
package cn.bugstack.ai.domain.agent.model.valobj;
