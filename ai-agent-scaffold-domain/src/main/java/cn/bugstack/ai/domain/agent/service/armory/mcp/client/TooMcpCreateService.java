package cn.bugstack.ai.domain.agent.service.armory.mcp.client;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 MCP 构建服务
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/2 09:31
 */
public interface TooMcpCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}
