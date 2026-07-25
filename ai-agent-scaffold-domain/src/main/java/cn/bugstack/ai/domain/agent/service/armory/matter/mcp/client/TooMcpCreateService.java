package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/** 将一种 MCP 连接配置转换为 Spring AI 工具回调。 */
public interface TooMcpCreateService {

    /** 建立连接、完成 MCP 初始化并返回服务端声明的工具。 */
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}
