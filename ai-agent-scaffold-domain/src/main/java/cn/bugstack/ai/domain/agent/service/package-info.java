/**
 * Agent 领域服务层：对话编排与运行时装配都在这里落地。
 *
 * <p>包含两条主线：一条是 {@code ChatService}，负责建会话、存消息、装上下文、调大模型或跑工作流，
 * 并把执行过程做成事件流交给上层；另一条是 {@code armory} 子包，负责在启动或热更新时把数据库/配置文件里的
 * Agent 配置装配成可直接执行的 ADK Runner。</p>
 *
 * <p>谁会调用它：trigger 层的 HTTP 控制器、定时任务和管理端接口。</p>
 *
 * <p>它不负责什么：不解析 HTTP 参数、不做 SSE 推送、不直接写 SQL（写库都通过 adapter 层的仓储接口）。</p>
 */
package cn.bugstack.ai.domain.agent.service;
