package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 在本机拉起一个 MCP 工具子进程，通过标准输入输出和它通信。
 *
 * <p>解决什么问题：很多现成的 MCP 工具是以命令行程序形式发布的（npx、python 脚本等），
 * 没有 HTTP 服务。这里按配置启动进程，握手拿到工具清单，之后所有工具调用都复用这个进程。</p>
 *
 * <p>所属层次：领域层的装配辅料实现。</p>
 *
 * <p>谁会调用它：{@code DefaultMcpClientFactory} 在配置里填了 stdio 参数时选中它。</p>
 *
 * <p>它不负责什么：不负责进程的重启和回收——进程生命周期跟着运行时 Agent，进程存活期间一直保持。
 * 也不校验命令是否存在，宿主机上没装对应程序时会在启动进程时失败。</p>
 *
 * <p>安全提示：命令、参数和环境变量完全来自受控配置文件，绝不能让模型或用户输入拼进来，
 * 否则等于开放了任意命令执行。</p>
 */
@Slf4j
@Service
public class StdioToolMcpCreateService implements TooMcpCreateService {

    /**
     * 启动子进程、完成 MCP 握手，返回它声明的工具清单。
     *
     * <p>各层职责：
     * 第一层：从配置取出子进程的启动方式（命令、参数、环境变量）。
     * 第二层：组装进程启动参数，全部来自配置，不接受运行时拼接。
     * 第三层：创建同步 MCP 客户端——这一步会真正启动子进程，并设置单次调用超时。
     * 第四层：执行 MCP 初始化握手，拿到服务端能力声明。
     * 第五层：把已握手的客户端包成工具清单返回。</p>
     *
     * <p>数据流：
     * stdio 配置
     * → 命令 + 参数 + 环境变量
     * → 启动子进程并建立 stdio 通道
     * → MCP 初始化握手
     * → 工具清单
     * → 返回给装配链挂到模型上</p>
     *
     * <p>任何一步失败都会抛异常，导致整张 Agent 配置不发布——缺了工具的 Agent 不如直接不可用。</p>
     */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        // 第一层：取出 stdio 配置段，里面有工具名、超时和进程启动参数。
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters stdioConfig = toolMcp.getStdio();

        // 取出进程启动的三要素：可执行命令、命令行参数、环境变量。
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters serverParameters = stdioConfig.getServerParameters();

        // 第二层：命令、参数和环境变量完全来自受控配置，不接受模型运行时拼接。
        ServerParameters stdioParams = ServerParameters.builder(serverParameters.getCommand())
                .args(serverParameters.getArgs())
                .env(serverParameters.getEnv())
                .build();

        // 第三层：客户端创建会启动子进程；初始化失败时整张 Agent 配置不发布。
        McpSyncClient mcpSyncClient = McpClient.sync(new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(Duration.ofSeconds(stdioConfig.getRequestTimeout())).build();

        // 第四层：同步完成 MCP 握手；握手结果只用于确认连通，工具清单从下面的提供器取。
        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        // 第五层：ToolCallback 复用该进程连接，生命周期随运行时 Agent。
        return SyncMcpToolCallbackProvider.builder().mcpClients(mcpSyncClient).build()
                .getToolCallbacks();
    }

}
