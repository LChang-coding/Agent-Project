package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责在本机启动一个子进程，并通过它的标准输入输出与 MCP 服务器通信。
 *
 * <p>所属层次：领域层的传输适配实现，注册为 Spring 组件后由 {@code McpProtocolClientSupport} 按传输类型索引使用。</p>
 *
 * <p>为什么这条路径格外危险：它会在服务器上真的 fork 一个进程并注入环境变量，
 * 等于把「在本机执行命令」的能力开放出去。因此上游的发布服务规定只有租户 owner/admin 才能创建
 * stdio 类型的 MCP，普通成员连配置都配不了。这里假定权限已经在上游把过关，只负责把参数解析对。</p>
 *
 * <p>它向下调用什么：MCP 官方客户端的 stdio 传输实现，以及操作系统的进程创建能力。</p>
 *
 * <p>它不负责什么：不做权限判断、不发协议请求、不解析工具清单、不做审计。</p>
 */
@Component
public class StdioMcpTransportClientFactory implements McpTransportClientFactory {

    /**
     * 没有显式配置超时时使用的秒数。
     *
     * <p>子进程可能因为环境不全、依赖缺失而卡在启动阶段一声不响，没有超时的话调用线程会被永久挂住，
     * 并发一多就把线程池耗尽，最终整个对话服务不可用。</p>
  */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * 用来解析参数数组和环境变量对象这两段 JSON 文本；同时也交给 MCP 客户端做协议报文的序列化。
     *
     * <p>无状态，可以安全复用一个实例，不需要每次调用新建。</p>
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 声明本工厂只处理 stdio 这一种传输方式。
     *
     * <p>只在启动建索引时调用一次；忽略大小写，避免用户填 STDIO 匹配不上。</p>
     */
    @Override
    public boolean supports(String transportType) {
   // 忽略大小写比较，兼容用户各种写法。
        return "stdio".equalsIgnoreCase(transportType);
    }

    /**
     * 在不真正启动进程的情况下检查命令、参数、环境变量能不能用。
     *
     * <p>做法是走一遍进程参数组装逻辑：能组装出来就算合法，缺命令或 JSON 格式不对会抛业务异常。</p>
     *
     * <p>意义在于把错配置挡在入库之前——如果等到用户对话时才失败，模型会拿到一段错误文案，
     * 那段文案还会被带进下一轮提示词，影响后续回答质量。</p>
 */
    @Override
    public void validate(McpConnectionConfigEntity configuration) {
        // 复用组装逻辑做校验，结果丢弃；能组装说明配置结构完整。
        toServerParameters(configuration);
    }

    /**
     * 按配置创建一个通过子进程通信的 MCP 同步客户端。
     *
  * <p>数据流：连接配置 → 校验并组装进程参数（命令 + 参数数组 + 环境变量）→ 确定超时
 * → 构建 stdio 传输（此处会启动子进程）→ 包成同步客户端返回。</p>
     *
     * <p>同一个超时值同时用于请求和初始化两个阶段，两处都可能卡住，只保护一处等于没保护。</p>
     *
     * <p>返回的客户端还没初始化，且必须由调用方关闭——不关的话子进程会残留，久了会把机器的进程数占满。
     * 配置非法时抛业务异常，此时不会启动任何进程。</p>
     */
    @Override
    public McpSyncClient create(McpConnectionConfigEntity configuration) {
        // 先把配置翻译成操作系统能理解的进程参数；任何格式问题都在这一步暴露，避免带着坏参数去起进程。
        ServerParameters parameters = toServerParameters(configuration);
        // 确定超时时长，配置缺失时套用默认值，保证永远有上限。
        Duration timeout = Duration.ofSeconds(timeoutSeconds(configuration));
        // 建立 stdio 传输（这一步会真的启动子进程），并把请求与初始化超时都设上，避免子进程不吭声时永久等待。
        return McpClient.sync(new StdioClientTransport(parameters, new JacksonMcpJsonMapper(objectMapper)))
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    /**
     * 把连接配置翻译成启动子进程所需的完整参数。
   *
     * <p>数据流：连接配置 → 校验命令非空 → 命令去空格 → 解析参数数组 → 解析环境变量 → 返回进程参数。</p>
     *
     * <p>命令为空直接抛业务异常：没有命令就无从启动进程，不做任何默认猜测，
     * 因为「猜一个命令去执行」在安全上是完全不可接受的。</p>
     */
    private ServerParameters toServerParameters(McpConnectionConfigEntity configuration) {
        // 命令是这条路径唯一的必填项，缺了就没有可执行目标，立刻失败而不是尝试默认值。
        if (configuration == null || blank(configuration.getCommand())) {
 // 用明确错误码告诉上层是命令缺失，便于界面给出针对性提示。
            throw new AppException("TOOL_MCP_STDIO_COMMAND_EMPTY", "stdio MCP command 不能为空");
        }
        // 命令两端的空格去掉后作为可执行文件，再挂上解析好的参数数组和环境变量。
        return ServerParameters.builder(configuration.getCommand().trim())
                .args(parseArgs(configuration.getArgs()))
                .env(parseEnv(configuration.getEnv()))
                .build();
    }

    /**
     * 把「JSON 字符串数组」形式的配置文本解析成命令行参数列表。
     *
     * <p>数据流：JSON 文本 → 判空（空则视为无参数）→ 解析成语法树 → 确认是数组
     * → 逐项确认是字符串 → 收集成列表返回。</p>
     *
     * <p>为什么逐项都要求是字符串：这些值会被直接拼成命令行参数交给操作系统。
     * 允许数字、对象等类型会带来隐式转换和意料之外的拼接结果，统一要求字符串最安全也最可预测。</p>
     *
     * <p>任何格式问题都抛业务异常，不做部分接受——宁可整条配置失败，也不带着一半参数去启动进程。</p>
     */
    private List<String> parseArgs(String args) {
     // 没填参数是完全正常的情况，返回空列表表示「只执行命令本身」。
        if (blank(args)) {
   // 用不可变空列表，避免下游误改。
            return List.of();
        }
// 解析过程可能抛各种解析异常，统一接住后翻译成对外错误码。
        try {
      // 先解析成语法树，便于逐项检查类型，而不是直接反序列化成 List 让 Jackson 悄悄做类型转换。
            JsonNode node = objectMapper.readTree(args);
   // 顶层必须是数组，否则语义完全不同（比如给了个对象），不能继续。
            if (!node.isArray()) {
     // 明确指出期望的形态，方便配置者修正。
                throw new AppException("TOOL_MCP_STDIO_ARGS_INVALID", "stdio MCP args 必须是 JSON 字符串数组");
            }
     // 收集校验通过的参数，保持配置里的原始顺序——命令行参数顺序是有意义的。
            List<String> result = new ArrayList<>();
   // 逐项检查并收集。
            for (JsonNode item : node) {
    // 非字符串项一律拒绝，避免隐式转换产生意料之外的命令行内容。
                if (!item.isTextual()) {
       // 整条配置失败，不接受「跳过坏项继续」，那会让实际执行的命令与配置者的预期不符。
                    throw new AppException("TOOL_MCP_STDIO_ARGS_INVALID", "stdio MCP args 必须全部为字符串");
                }
   // 取出文本值加入参数列表。
                result.add(item.asText());
            }
       // 返回按原顺序排列的参数列表。
            return result;
        } catch (AppException e) {
   // 上面主动抛出的业务异常已经带了准确文案，原样向上传递，不要被下面的兜底覆盖掉。
            throw e;
        } catch (Exception e) {
     // 走到这里说明连 JSON 都解析不了（比如是一段随手写的文本），给出统一的格式错误提示。
            throw new AppException("TOOL_MCP_STDIO_ARGS_INVALID", "stdio MCP args 必须是合法 JSON 字符串数组");
        }
    }

    /**
     * 把「JSON 字符串对象」形式的配置文本解析成子进程的环境变量表。
     *
   * <p>数据流：JSON 文本 → 判空（空则视为无环境变量）→ 解析成语法树 → 确认是对象
     * → 逐个字段确认值是字符串 → 按原顺序收集成表返回。</p>
     *
     * <p>安全提醒：这里的值通常就是外部服务的 API Key、Token。它们只会被注入到子进程的环境里，
     * 绝不能出现在日志、异常文案、给大模型的工具描述或调用结果中。
     * 也正因如此，下面所有报错文案都只说「格式不对」，不回显任何具体的键值内容。</p>
     */
    private Map<String, String> parseEnv(String env) {
        // 不配环境变量是常见情况，返回空表表示子进程沿用默认环境。
        if (blank(env)) {
   // 用不可变空表，避免下游误改。
            return Map.of();
        }
        // 解析可能失败，统一接住并翻译成对外错误码，注意文案里不能带上原始内容。
        try {
    // 先解析成语法树，逐项检查类型，避免 Jackson 把数字之类的值悄悄转成字符串。
            JsonNode node = objectMapper.readTree(env);
    // 顶层必须是对象（键值对），数组或标量都不符合环境变量的语义。
            if (!node.isObject()) {
    // 只提示期望形态，不回显用户填的内容，防止密钥被写进错误日志。
                throw new AppException("TOOL_MCP_STDIO_ENV_INVALID", "stdio MCP env 必须是 JSON 字符串对象");
            }
      // 用保序表收集，便于排查时与配置顺序对照。
            Map<String, String> result = new LinkedHashMap<>();
// 取出全部字段逐个校验。
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    // 遍历每个键值对。
            while (fields.hasNext()) {
   // 取出当前字段。
                Map.Entry<String, JsonNode> field = fields.next();
     // 环境变量的值在操作系统层面只能是字符串，非字符串一律拒绝而不做隐式转换。
                if (!field.getValue().isTextual()) {
      // 报错只说明哪类问题，不带出键名对应的值，避免密钥外泄。
                    throw new AppException("TOOL_MCP_STDIO_ENV_INVALID", "stdio MCP env 的值必须为字符串");
                }
    // 校验通过后放入结果表，稍后注入子进程环境。
                result.put(field.getKey(), field.getValue().asText());
            }
    // 返回完整的环境变量表。
            return result;
        } catch (AppException e) {
      // 保留上面精确的业务异常，不被兜底分支覆盖。
            throw e;
        } catch (Exception e) {
   // 连 JSON 都解析不了时给出统一提示，同样不回显原始内容。
            throw new AppException("TOOL_MCP_STDIO_ENV_INVALID", "stdio MCP env 必须是合法 JSON 字符串对象");
        }
    }

    /**
     * 算出本次要用的超时秒数，保证结果一定是个正数。
     *
     * <p>配置缺失或小于 1 秒都套用默认 30 秒。不允许 0 或负数，因为在部分实现里它们意味着永不超时，
     * 而卡死的子进程会一直占着线程和系统资源。</p>
     */
    private int timeoutSeconds(McpConnectionConfigEntity configuration) {
        // 三种异常情况一并兜底成默认值，确保永远有超时上限。
        return configuration == null || configuration.getTimeoutSeconds() == null || configuration.getTimeoutSeconds() < 1
                ? DEFAULT_TIMEOUT_SECONDS : configuration.getTimeoutSeconds();
    }

    /**
     * 判断一段配置文本是否等于没填（空引用或全是空白字符）。
     *
     * <p>只判 null 不够：用户在界面上很容易留下空字符串或几个空格，
  * 那种值如果被当成有效配置传下去，会变成一个空命令或空 JSON，报错位置离真正原因很远。</p>
     */
    private boolean blank(String value) {
      // 空引用和纯空白都算没填，避免空串被当成有效命令或有效 JSON 传下去。
        return value == null || value.isBlank();
    }
}
