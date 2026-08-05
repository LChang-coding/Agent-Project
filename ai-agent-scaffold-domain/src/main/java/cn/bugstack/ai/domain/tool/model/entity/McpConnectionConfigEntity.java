package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 建立一条 MCP 连接所需的全部参数，把「远程 SSE」和「本机 stdio 子进程」两种方式统一成一个入参对象。
 *
 * <p>所属层次：工具领域的实体（连接参数对象），不落库。它是从 MCP 版本记录或运行时工具目录里摘出来的字段子集。</p>
 *
 * <p>谁构造它：{@code McpProtocolClientSupport} 在测试连接时从版本实体摘、在真实调用时从工具目录摘；
 * {@code ToolPublishService} 在创建 MCP 时也用它做一次配置合法性预检。</p>
 *
 * <p>谁消费它：{@code McpTransportClientFactory} 的两个实现——SSE 工厂只看地址和超时，stdio 工厂只看命令、参数和环境变量。</p>
 *
 * <p>为什么要单独抽出来：这样传输层完全不需要认识「MCP 版本」「工具目录」这些业务对象，
 * 换存储结构也不影响建连代码。</p>
 *
 * <p>它不负责什么：不做权限判断（能不能用 stdio 已在创建工具时由发布服务拦过）、不发起协议请求、不解析工具清单。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConnectionConfigEntity {

/**
     * 传输类型（sse 或 stdio）；协议客户端按它去工厂索引里查唯一的实现，查不到就直接抛「传输类型不支持」，
     * 不会尝试猜测或降级。
     */
    private String transportType;

    /**
     * SSE 模式下的服务地址；建连前会被拆成主机部分和 SSE 路径两段，为空直接报错。
     * 地址里可能带着鉴权查询串，属于外部系统的接入凭证，不应在日志里原样打印。
     */
    private String endpoint;

    /**
     * stdio 模式下要启动的可执行命令；它会在服务器上真的 fork 一个子进程，
  * 因此这类配置只允许租户管理员创建，普通成员填了会在创建阶段就被拒。
     */
    private String command;

    /**
     * stdio 模式下的命令行参数，必须是 JSON 字符串数组文本；解析时逐项要求是字符串，
     * 不合法就在建连前失败，避免把畸形参数传进子进程。
     */
    private String args;

    /**
     * stdio 模式下注入子进程的环境变量，必须是 JSON 字符串对象文本。
     * 外部服务的 API Key、Token 通常就放在这里，属于最高敏感级别：只在启动子进程那一刻使用，
     * 不写日志、不回传给大模型，也不出现在给模型看的工具描述中。
     */
    private String env;

    /**
     * 这次 MCP 请求的超时秒数；两个工厂都会用它同时设置连接超时、请求超时和初始化超时。
     * 为空或小于 1 时由工厂各自套用 30 秒默认值，防止一个卡死的远程服务把调用线程永久挂住。
     */
    private Integer timeoutSeconds;
}
