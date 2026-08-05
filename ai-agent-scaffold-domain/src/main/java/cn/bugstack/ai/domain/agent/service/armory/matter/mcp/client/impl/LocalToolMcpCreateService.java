package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 把 Spring 容器里已有的本地工具，按 MCP 工具的形式交给模型使用。
 *
 * <p>解决什么问题：有些工具就在本进程里（比如查本系统数据的工具），没必要为它开一个 MCP 服务再连回来。
 * 这里直接按 Bean 名取出来包装成工具清单，零进程、零网络开销。</p>
 *
 * <p>所属层次：领域层的装配辅料实现。</p>
 *
 * <p>谁会调用它：{@code DefaultMcpClientFactory} 在配置里填了 local 参数时选中它。</p>
 *
 * <p>它不负责什么：不建立任何连接、不做超时控制（本地调用没有网络超时概念）。</p>
 */
@Slf4j
@Service
public class LocalToolMcpCreateService  implements TooMcpCreateService {

    /**
     * Spring 容器句柄，用来按配置里写的 Bean 名把本地工具提供器取出来。
     */
    @Resource
    protected ApplicationContext applicationContext;

    /**
     * 按配置里的 Bean 名取出本地工具提供器，返回它声明的工具清单。
     *
     * <p>数据流：本地工具配置 → 取出 Bean 名 → 从容器解析工具提供器 → 取出工具清单 → 返回。</p>
     *
     * <p>Bean 名写错或类型不是工具提供器时会直接抛异常，让装配失败。
     * 这里刻意不做兜底返回空数组——那样 Agent 会安静地少掉一批工具，问题极难发现。</p>
     */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        // 取出本地工具的配置段，里面只有一个 Bean 名。
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        // 单独留一份名字用于日志，便于确认到底挂上了哪个本地工具。
        String name = local.getName();

        // 名称不存在或类型错误时立即失败，禁止返回空工具集掩盖配置问题。
        ToolCallbackProvider localToolCallbackProvider = (ToolCallbackProvider) applicationContext.getBean(local.getName());
        // 记录本地工具已就绪，启动日志里可以核对工具数量和名称。
        log.info("tool local mcp initialize {}", name);

        // 返回该提供器声明的全部工具，交给装配链挂到模型上。
        return localToolCallbackProvider.getToolCallbacks();
    }

}
