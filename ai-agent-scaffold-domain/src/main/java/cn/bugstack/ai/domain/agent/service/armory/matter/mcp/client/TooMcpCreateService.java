package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 把一种 MCP 连接方式变成模型可以直接调用的工具清单。
 *
 * <p>解决什么问题：MCP 工具有三种接入方式（远程 SSE、本地子进程、JVM 内实现），连接手法完全不同，
 * 但装配链只想拿到统一的「工具回调数组」。这个接口就是那道统一出口。</p>
 *
 * <p>所属层次：领域层的装配辅料接口（策略接口）。</p>
 *
 * <p>谁会调用它：{@code DefaultMcpClientFactory} 按配置选出具体实现后调用它。</p>
 *
 * <p>它不负责什么：不决定用哪种实现（那是工厂的事）、不管理连接的关闭
 * （连接生命周期跟着装配出来的 Agent，进程存活期间一直保持）。</p>
 */
public interface TooMcpCreateService {

    /**
     * 建立连接、完成 MCP 握手，返回服务端声明的全部工具。
     *
     * <p>这一步会发生真实的副作用：远程方式会发起 HTTP 连接，子进程方式会在本机拉起进程。
     * 因此装配阶段就能发现工具服务不可用，而不是等到用户对话时才失败。</p>
     *
     * <p>失败时抛异常，导致整张配置表装配失败、这个 Agent 不会被注册——这是刻意的，
     * 缺了工具的 Agent 行为和预期差别很大，不如直接不可用。</p>
     */
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}
