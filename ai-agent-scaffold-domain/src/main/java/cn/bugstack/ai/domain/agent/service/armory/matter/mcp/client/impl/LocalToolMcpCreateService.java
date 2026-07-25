package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/** 把 Spring 容器中的本地工具提供器暴露为 MCP 风格工具集合。 */
@Slf4j
@Service
public class LocalToolMcpCreateService  implements TooMcpCreateService {

    /** 按配置 Bean 名称解析本地工具提供器。 */
    @Resource
    protected ApplicationContext applicationContext;

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        String name = local.getName();

        // 名称不存在或类型错误时立即失败，禁止返回空工具集掩盖配置问题。
        ToolCallbackProvider localToolCallbackProvider = (ToolCallbackProvider) applicationContext.getBean(local.getName());
        log.info("tool local mcp initialize {}", name);

        return localToolCallbackProvider.getToolCallbacks();
    }

}
