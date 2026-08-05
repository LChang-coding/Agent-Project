package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 老代码里遗留的 MCP SSE 入口，现在只是一层壳，所有实际工作都转交给统一的 MCP 协议客户端。
 *
 * <p>所属层次：领域层的兼容适配器，已标记废弃。</p>
 *
 * <p>为什么留着它：早期只支持 SSE，方法签名是围绕 SSE 设计的；后来加了 stdio，协议交互统一收拢到
 * {@code McpProtocolClientSupport}。为了不一次性改动所有历史调用点，这里保留原来的方法名做转发。
 * 新代码请直接用 {@code McpProtocolClientSupport}，不要再往这里加东西。</p>
 *
 * <p>它向下调用什么：只调用统一协议客户端；自己不建连接、不解析协议、不管超时。</p>
 *
 * <p>它不负责什么：不做权限判断、不做幂等和审计（那些在工具网关里）、不写库。</p>
 */
@Deprecated
@Component
public class McpSseClientSupport {

    /** 真正干活的统一 MCP 协议客户端；本类所有方法都是原样转发给它，不额外加逻辑，避免两套实现行为不一致。 */
    private final McpProtocolClientSupport mcpProtocolClientSupport;

    /**
     * 注入统一协议客户端完成构造。
     *
     * <p>只做装配，不持有任何连接：MCP 客户端一律由单次操作现建现关，不在这里缓存。</p>
     */
    public McpSseClientSupport(McpProtocolClientSupport mcpProtocolClientSupport) {
        this.mcpProtocolClientSupport = mcpProtocolClientSupport;
    }

  /**
     * 按 MCP 版本里冻结的连接配置去拉远程工具清单。
     *
     * <p>会真的建立外部连接并发 initialize + tools/list，返回可直接冻结到版本里的清单快照 JSON。
     * 连不上或协议报错时抛业务异常。</p>
     */
    public String listToolsSchema(McpVersionEntity version) {
     // 原样转发，不在这里做任何判断，保证与新入口行为完全一致。
        return mcpProtocolClientSupport.listToolsSchema(version);
    }

    /**
     * 只给一个 SSE 地址就去拉远程工具清单，供「填完地址点测试」这类还没有版本记录的场景使用。
     *
     * <p>会真的建立外部连接。地址为空或连不上时抛业务异常。</p>
     */
    public String listToolsSchema(String endpoint) {
        // 现场拼一个只有传输类型和地址的临时版本对象，让下游按标准 SSE 流程处理，避免另写一套建连代码。
        return mcpProtocolClientSupport.listToolsSchema(McpVersionEntity.builder()
                .transportType("sse")
                .endpoint(endpoint)
                .build());
    }

    /**
     * 调用远程 MCP 上的某个具体工具。
   *
     * <p>输入是工具目录项（提供冻结的连接参数）、远程工具名和参数表；返回远程执行结果文本。
     * 会真的产生外部副作用，远程报错时抛业务异常。</p>
     */
    public String callTool(ToolCatalogEntity tool, String toolName, Map<String, Object> arguments) {
        // 原样转发给统一客户端，由它负责建连、初始化、调用、错误转换和结果长度裁剪。
        return mcpProtocolClientSupport.callTool(tool, toolName, arguments);
    }

    /**
   * 从工具清单快照里挑出所有可用的远程工具名。
     *
     * <p>只解析已保存的 JSON，不发起任何网络请求；快照为空或格式坏掉时返回空列表而不是抛异常。</p>
     */
    public List<String> toolNames(String schemaJson) {
        // 转发解析逻辑，保持与新入口同一套容错策略。
        return mcpProtocolClientSupport.toolNames(schemaJson);
    }

    /**
     * 把工具清单拼成一句给人或给模型看的摘要文案。
     *
     * <p>清单为空时不返回空串，而是返回一句「尚未完成测试」的提示。因为这段文案会被展示出来，
     * 空串只会让人以为界面坏了，而明确的提示能引导用户先去点测试。</p>
     */
    public String toolSummary(String schemaJson) {
   // 先解析出工具名列表，作为「有没有测过」的判断依据。
        List<String> toolNames = toolNames(schemaJson);
        // 空列表说明还没成功拉过清单，给出可操作的提示；否则用分号把工具名连起来。
        return toolNames.isEmpty()
                ? "当前 MCP 尚未完成测试，未拉取到远程工具清单。"
                : String.join("；", toolNames);
    }
}
