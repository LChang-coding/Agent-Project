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

/**
 * 根据配置里填了哪一种连接参数，挑出对应的 MCP 工具构造器。
 *
 * <p>解决什么问题：配置里 local / sse / stdio 三个字段是互斥的，只会填一个。
 * 这里把「看哪个字段有值」这个判断集中在一处，避免每个调用点各写一遍。</p>
 *
 * <p>所属层次：领域层的装配辅料工厂。</p>
 *
 * <p>谁会调用它：需要为某个 Agent 或模型建立 MCP 工具连接的装配逻辑。</p>
 *
 * <p>它不负责什么：不建立连接、不校验参数内容，只做选择。</p>
 */
@Slf4j
@Service
public class DefaultMcpClientFactory {

    /**
     * JVM 内工具的构造器：直接从 Spring 容器按名取工具，不走进程也不走网络。
     */
    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;

    /**
     * 远程 SSE 工具的构造器：通过 HTTP 长连接访问外部工具服务。
     */
    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;

    /**
     * 本地子进程工具的构造器：在宿主机拉起一个进程，用标准输入输出通信。
     */
    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    /**
     * 按配置里存在的连接参数选出构造器。
     *
     * <p>判断顺序即冲突优先级：如果配置不小心同时填了多个，local 优先于 SSE，SSE 优先于 stdio。
     * 这个顺序是按「代价从小到大」排的——本地实现最轻，子进程最重。</p>
     *
     * <p>三个都没填时直接抛异常拒绝装配：留一条什么都没配的工具项，
     * 只会让 Agent 少一个能力却毫无提示，比启动失败更难排查。</p>
     */
    public TooMcpCreateService getTooMcpCreateService(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        // 填了本地参数就走 JVM 内实现，开销最小，优先命中。
        if (null != toolMcp.getLocal()) return localToolMcpCreateService;
        // 填了 SSE 参数就走远程 HTTP 连接。
        if (null != toolMcp.getSse()) return sseToolMcpCreateService;
        // 填了 stdio 参数就走本机子进程。
        if (null != toolMcp.getStdio()) return stdioToolMcpCreateService;
        // 一个都没填说明这条工具配置是空壳，拒绝装配而不是静默跳过。
        throw new AppException(ResponseCode.NOT_FOUND_METHOD.getCode(), ResponseCode.NOT_FOUND_METHOD.getInfo());
    }

}
