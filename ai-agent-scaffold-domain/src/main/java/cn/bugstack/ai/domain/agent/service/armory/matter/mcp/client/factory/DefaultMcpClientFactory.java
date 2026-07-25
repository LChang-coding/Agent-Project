package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.factory;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl.LocalToolMcpCreateService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl.SSEToolMcpCreateService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl.StdioToolMcpCreateService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/** 按配置中唯一存在的传输块选择 MCP 工具构造器。 */
@Slf4j
@Service
public class DefaultMcpClientFactory {

    /** 解析已在 Spring 容器注册的本地 ToolCallbackProvider。 */
    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;

    /** 通过远端 HTTP SSE 建立 MCP 客户端。 */
    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;

    /** 启动本机子进程并通过标准输入输出通信。 */
    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    /** 选择顺序即冲突优先级：local、SSE、Stdio；三者都缺失则拒绝装配。 */
    public TooMcpCreateService getTooMcpCreateService(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (null != toolMcp.getLocal()) return localToolMcpCreateService;
        if (null != toolMcp.getSse()) return sseToolMcpCreateService;
        if (null != toolMcp.getStdio()) return stdioToolMcpCreateService;
        throw new AppException(ResponseCode.NOT_FOUND_METHOD.getCode(), ResponseCode.NOT_FOUND_METHOD.getInfo());
    }

}
