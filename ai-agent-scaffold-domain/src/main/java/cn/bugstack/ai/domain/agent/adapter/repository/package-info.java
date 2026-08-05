/**
 * Agent 领域的数据出入口：只声明「要读什么、要写什么」，不写一行 SQL。
 *
 * <p>这里的接口由基础设施层用 MyBatis 实现，落到 ai_agent 系列配置表和租户覆盖表上。
 * 领域服务通过这些接口读取 Agent 配置、读写租户级启停状态，因此租户隔离的入参（tenantId）
 * 必须由调用方一路带下来，缺失会导致读到别人租户的配置。</p>
 */
package cn.bugstack.ai.domain.agent.adapter.repository;
