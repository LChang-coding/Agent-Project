package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 和外部 MCP 服务器说话的统一出口：拉工具清单、调用远程工具，都从这里走。
 *
 * <p>所属层次：领域层的协议支撑组件。它把「不同传输方式」的差异挡在下面（SSE 还是 stdio 由工厂决定），
 * 把「协议交互」的细节挡在上面（调用方只管传工具名和参数）。</p>
 *
 * <p>谁调用它：{@code ToolPublishService}（测试 MCP 时拉工具清单）、{@code ToolGateway}（模型真正调用远程工具时）、
 * 以及废弃的兼容适配器 {@code McpSseClientSupport}。</p>
 *
 * <p>连接策略：每次操作现建客户端、用完即关，不做任何连接池或客户端复用。
 * 原因有两个：一是 MCP 会话是有状态的，复用会让上一次调用的状态串到下一次；
 * 二是不同租户配的 MCP 可能指向不同服务器和不同凭证，复用连接等于埋下跨租户串用的隐患。
 * 代价是每次调用都要多花一次建连时间，这个代价换的是正确性和隔离性。</p>
 *
 * <p>结果处理三原则：远程结果会被裁剪到长度上限（否则一次调用就能把模型上下文撑爆）；
 * 远程标记了错误就翻译成领域异常（而不是把错误内容当成正常结果喂给模型）；
 * 解析类失败一律降级成空结果而不是抛异常（清单解析不出来不该让整轮对话失败）。</p>
 *
 * <p>它不负责什么：不做权限判断、不做幂等与审计、不决定该调哪个工具，这些都在工具网关和发布服务里。</p>
 */
@Component
public class McpProtocolClientSupport {

    /**
     * 返回给大模型的结果文本长度上限。
     *
  * <p>为什么必须裁剪：远程工具可能返回几十万字的内容。这段文本会被塞进下一轮提示词，
     * 一旦超出模型上下文窗口，要么请求直接失败，要么把用户前面的对话历史挤出去，
     * 表现就是「模型突然忘了前面说过什么」。裁剪虽然会丢尾部内容，但比整轮对话崩掉好。</p>
  */
    private static final int MAX_RESULT_LENGTH = 16_000;

/**
 * 传输类型到客户端工厂的索引表，键是小写的传输类型。
     *
     * <p>这就是这一层的「路由表」：拿到工具的传输类型直接查表，查不到就失败关闭，不猜也不降级。
     * 注意同一个传输类型只能有一个工厂，若将来出现两个实现都声称支持 sse，后放入的会覆盖先放入的，
     * 具体用哪个取决于 Spring 给出的 Bean 顺序，属于难排查的隐患。</p>
     */
    private final Map<String, McpTransportClientFactory> factories;
    /**
     * 用来序列化工具清单快照、结构化结果和 MCP 参数。
     *
     * <p>无状态可复用；它只做数据搬运，不承载任何业务规则。</p>
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 启动时把 Spring 注入的所有传输工厂整理成一张按传输类型索引的路由表。
     *
     * <p>输入是容器里所有的工厂实现；构造完成后这张表就固定不变，运行期不再修改，因此天然线程安全。</p>
     *
     * <p>为什么用「问工厂支持什么」而不是「工厂自报键名」：让判断标准集中在工厂自己的 supports 方法里，
     * 新增一种传输方式只需要实现接口并注册成组件，这里一行都不用改。</p>
     */
    public McpProtocolClientSupport(List<McpTransportClientFactory> factories) {
        // 用保序表，便于排查时按注册顺序观察都装配了哪些工厂。
        this.factories = new LinkedHashMap<>();
        // 逐个询问每个工厂支持哪种传输方式，并登记到对应的键上。
        for (McpTransportClientFactory factory : factories) {
            // 支持 SSE 的登记到 sse 键；同一个键被重复登记时后者会覆盖前者。
            if (factory.supports("sse")) {
       // 登记到 sse 键上，后续按传输类型即可直接命中它。
                this.factories.put("sse", factory);
            }
    // 支持 stdio 的登记到 stdio 键，规则同上。
            if (factory.supports("stdio")) {
          // 登记到 stdio 键上，与 SSE 各自独立互不影响。
                this.factories.put("stdio", factory);
            }
        }
    }

    /**
     * 在不真正建连的前提下校验一份 MCP 连接配置。
  *
     * <p>先按传输类型选出工厂（类型不支持会立刻失败），再让工厂用自己的规则校验。
     * 用于创建 MCP 时的前置拦截，把错配置挡在入库之前。</p>
     */
    public void validate(McpConnectionConfigEntity configuration) {
 // 选工厂和校验合成一步：类型不支持在选工厂时就失败，配置不合法在校验时失败。
        factory(configuration).validate(configuration);
    }

    /**
     * 连上远程 MCP 服务器，把它有哪些工具、每个工具怎么用（入参 schema）全部拉回来。
     *
     * <p>各层职责：
     * 第一层：从版本记录里摘出冻结的连接参数，版本不存在就直接失败。
     * 第二层：按传输类型建客户端，并用 try-with-resources 保证连接或子进程一定被释放。
     * 第三层：按 MCP 协议先 initialize 再 listTools——不初始化就调用会被服务器拒绝。
     * 第四层：把传输方式、地址、工具清单、分页游标打包成一份快照 JSON。</p>
     *
     * <p>数据流：
     * MCP 版本记录
     * → 连接配置
     * → 建客户端（真的连接／起子进程）
     * → initialize
     * → listTools
 * → 组装快照
   * → 返回 JSON 文本</p>
     *
     * <p>这份快照的用途很关键：它会被冻结进 MCP 版本记录，之后「工具能力如何声明给大模型」
     * 完全以它为准——模型看到的可用远程工具摘要由它生成，模型没给工具名时也靠它推断。</p>
 *
     * <p>会发起外部连接，不写库（落库由发布服务负责）。连不上、初始化失败、协议报错都抛业务异常，
     * 且错误码按传输类型区分，便于按接入方式统计失败率。</p>
     */
    public String listToolsSchema(McpVersionEntity version) {
  // 从版本记录里摘出冻结的连接参数：用版本而不是草稿，保证测试的就是将来要发布的那套配置。
        McpConnectionConfigEntity configuration = fromVersion(version);
        // try-with-resources 保证无论成功失败，SSE 连接或子进程都会被关闭，不留资源泄漏。
        try (McpSyncClient client = factory(configuration).create(configuration)) {
       // 连接或子进程只覆盖本次协议操作。
            client.initialize();
            // 拉取远程工具清单，这是本方法的核心产出。
            McpSchema.ListToolsResult toolsResult = client.listTools();
     // 用保序表组装快照，字段顺序稳定便于人工比对不同时间的两次测试结果。
            Map<String, Object> schema = new LinkedHashMap<>();
   // 记下当时用的传输方式，将来排查「为什么行为变了」时能确认接入方式没被改过。
            schema.put("transportType", configuration.getTransportType());
        // 记下当时连的地址，同样用于事后核对；注意它可能含鉴权查询串，展示给前端时要谨慎。
            schema.put("endpoint", configuration.getEndpoint());
    // 工具清单本体：每个工具的名称、说明和入参 schema 都在这里，是声明给模型的能力来源。
            schema.put("tools", toolsResult.tools());
 // 分页游标；远程工具很多时会分页返回，保留它便于将来支持翻页拉全量。
            schema.put("nextCursor", toolsResult.nextCursor());
// 序列化成 JSON 文本返回，供发布服务冻结进版本记录。
            return toJson(schema);
        } catch (AppException e) {
         // 这是选工厂或建连时主动抛出的业务异常，错误码已经准确，原样上抛不要被下面的兜底覆盖。
            throw e;
        } catch (Exception e) {
  // 其余都是网络、协议、进程层面的意外故障；按传输类型给出不同错误码，只带一句可读原因。
            throw new AppException(listFailureCode(configuration), "MCP 工具列表获取失败：" + readableMessage(e), e);
        }
    }

    /**
     * 真正调用远程 MCP 上的某个工具，这是本类唯一会产生外部副作用的方法。
  *
     * <p>各层职责：
     * 第一层：先确认工具名非空。这个名字来自大模型，模型完全可能漏给或给错，
     *         不校验就会拿着空名字去调远程，得到一个含糊的协议错误。
 * 第二层：从工具目录项里摘出冻结的连接参数，工具为空就直接失败。
     * 第三层：建客户端并 initialize，用 try-with-resources 保证连接／子进程被释放。
     * 第四层：发起调用，参数为空时兜底成空表，避免协议层收到 null。
     * 第五层：把结构化内容和文本内容渲染成一段文本。
     * 第六层：如果远程明确标记了错误，就翻译成领域异常，而不是把错误内容当正常结果返回。
     * 第七层：正常结果按长度上限裁剪后返回。</p>
     *
     * <p>数据流：
     * 工具目录项 + 模型给的工具名和参数
     * → 校验工具名
     * → 连接配置
     * → 建客户端（真的连接／起子进程）
     * → initialize
     * → callTool（外部副作用在此发生）
     * → 渲染结果文本
   * → 判断远程错误标记
     * → 裁剪长度
     * → 返回文本</p>
     *
     * <p>为什么远程报错必须转成异常：调用方（工具网关）要靠异常来把这次调用的审计记录推进成失败状态。
     * 如果这里把错误内容当成正常结果返回，审计就会显示成功，事后完全查不出问题。</p>
     *
     * <p>会产生外部副作用，不写库。异常按传输类型区分错误码。</p>
     */
    public String callTool(ToolCatalogEntity tool, String toolName, Map<String, Object> arguments) {
        // 第一层：工具名来自大模型，必须先校验。缺名字就直接失败，不去猜也不去调，避免打到不确定的远程动作上。
        if (toolName == null || toolName.isBlank()) {
            // 明确的错误码让上层能把「模型没给工具名」和「远程执行失败」区分开。
            throw new AppException("TOOL_MCP_TOOL_NAME_EMPTY", "MCP 调用必须提供 toolName");
        }
        // 第二层：从已发布的工具目录项里摘连接参数，保证调用打到的是发布时确定的那台服务器。
        McpConnectionConfigEntity configuration = fromCatalog(tool);
  // 第三层：建客户端并确保用完释放；SSE 连接不关会泄漏，子进程不关会残留。
        try (McpSyncClient client = factory(configuration).create(configuration)) {
            // 按协议先握手，跳过这步远程会拒绝后续请求。
            client.initialize();
            // 第四层：真正发起调用，外部副作用（下单、发消息、写外部系统）就在这一行发生。
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, safeArguments(arguments)));
      // 第五层：把可能存在的结构化内容和多段文本内容合并成一段可读文本。
            String text = renderResult(result);
     // 第六层：远程明确标记了错误，就必须转成异常，否则审计会把失败记成成功。
            if (Boolean.TRUE.equals(result.isError())) {
              // 把远程的错误说明带上，模型下一轮能据此调整参数重试。
                throw new AppException("TOOL_MCP_REMOTE_ERROR", "MCP 远程工具执行失败：" + text);
            }
     // 第七层：按上限裁剪后返回，防止一次调用的返回值把模型上下文撑爆。
            return truncate(text);
        } catch (AppException e) {
       // 保留上面精确的业务异常（工具名缺失、远程报错），不要被兜底分支覆盖成笼统的调用失败。
            throw e;
        } catch (Exception e) {
            // 网络、协议、子进程层面的意外故障；按传输类型给出错误码，只带可读原因不带堆栈。
            throw new AppException(callFailureCode(configuration), "MCP 调用失败：" + readableMessage(e), e);
        }
    }

    /**
     * 从已保存的工具清单快照里挑出所有可用的远程工具名。
     *
  * <p>数据流：快照 JSON → 解析出工具条目列表 → 取出每项的 name → 过滤掉空名 → 返回名称列表。</p>
     *
     * <p>用途有三个：拼给模型看的可用工具摘要、模型没给工具名时判断能不能唯一推断、
  * 以及在报「缺少工具名」时把可选项列给模型看，让它下一轮能给对。</p>
     *
     * <p>纯解析，不发网络请求；快照为空或格式坏掉时返回空列表而不是抛异常。</p>
     */
    public List<String> toolNames(String schemaJson) {
     // 解析出工具条目后逐个取名字，并把空名字滤掉，避免给模型看到一个空选项。
        return tools(schemaJson).stream()
                .map(tool -> stringValue(tool.get("name")))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 按传输类型从路由表里取出对应的客户端工厂。
     *
     * <p>这是工具接入方式的分发点：查得到就用，查不到直接抛「传输类型不支持」。</p>
     *
     * <p>为什么不做默认降级：如果配置写的是 stdio 却被默认当成 SSE 去连，
     * 结果既连不上又给出误导性的错误信息，排查成本比直接失败高得多。</p>
     */
    private McpTransportClientFactory factory(McpConnectionConfigEntity configuration) {
  // 统一转小写再查表，兼容用户填 SSE、Stdio 等各种写法；配置缺失时用空串，后面必然查不到而失败。
        String transportType = configuration == null || configuration.getTransportType() == null
                ? "" : configuration.getTransportType().toLowerCase();
        // 查路由表。
        McpTransportClientFactory factory = factories.get(transportType);
   // 没有对应实现就失败关闭，把不支持的类型原样带进错误信息，便于确认到底配的是什么。
        if (factory == null) {
  // 把用户配的类型原样回显，便于确认是拼写错误还是真的用了未支持的接入方式。
            throw new AppException("TOOL_MCP_TRANSPORT_UNSUPPORTED", "MCP 传输类型不支持：" + transportType);
        }
        // 返回唯一匹配的工厂。
        return factory;
    }

    /**
     * 从 MCP 版本记录里摘出建连需要的那几个字段。
     *
     * <p>用版本（不可变快照）而不是定义（可随时改的草稿），保证测试出来的结论和将来发布运行的行为一致。
     * 版本为空说明上游没查到记录，直接失败而不是带着空配置去建连。</p>
     */
    private McpConnectionConfigEntity fromVersion(McpVersionEntity version) {
        // 版本不存在就没有任何可用参数，立刻失败。
        if (version == null) {
        // 版本查不到通常是数据被删或编号传错，直接失败让上层去核对参数。
            throw new AppException("TOOL_MCP_VERSION_NOT_FOUND", "MCP 版本不存在");
        }
        // 逐字段搬运：传输方式、地址、命令、参数、环境变量，正好覆盖两种传输方式各自需要的项。
        return McpConnectionConfigEntity.builder()
                .transportType(version.getTransportType())
                .endpoint(version.getEndpoint())
                .command(version.getCommand())
                .args(version.getArgs())
                .env(version.getEnv())
                .build();
    }

    /**
     * 从运行时工具目录项里摘出建连需要的那几个字段。
   *
     * <p>目录项本身就是「已发布版本」的投影，所以这里拿到的连接参数与发布时冻结的完全一致，
     * 有人在管理页改草稿不会影响正在进行的调用。工具为空说明上游解析出了问题，直接失败。</p>
     */
    private McpConnectionConfigEntity fromCatalog(ToolCatalogEntity tool) {
        // 工具为空意味着无法确定要连哪里，不做任何猜测。
        if (tool == null) {
     // 目录项为空说明上游解析环节出了问题，宁可报错也不带着空配置去连外部系统。
            throw new AppException("TOOL_NOT_FOUND", "工具不存在");
        }
        // 逐字段搬运，与从版本记录摘取时保持同样的字段集合。
        return McpConnectionConfigEntity.builder()
                .transportType(tool.getTransportType())
                .endpoint(tool.getEndpoint())
                .command(tool.getCommand())
                .args(tool.getArgs())
                .env(tool.getEnv())
                .build();
    }

    /**
     * 把 MCP 返回的多段内容拼成一段给大模型看的文本。
     *
     * <p>数据流：调用结果 → 结构化内容序列化成 JSON（若有）→ 逐段内容处理（文本直接取，
     * 非文本序列化成 JSON）→ 全部为空时退化成协议对象的字符串形式 → 用换行连接后返回。</p>
     *
 * <p>为什么要兼容这么多形态：MCP 协议允许工具返回结构化数据、纯文本、图片等多种内容块，
   * 而模型只能读文本。这里把能读的都转成文本，读不了的也至少给出可辨认的 JSON，
     * 总之不能返回空——空结果会让模型以为工具没工作，从而反复重试。</p>
     */
    private String renderResult(McpSchema.CallToolResult result) {
   // 收集所有可读片段，最后统一连接；用列表而不是直接拼字符串，便于中间做空值过滤。
        List<String> parts = new ArrayList<>();
    // 结构化内容优先：它往往是工具真正的返回数据，序列化成 JSON 后模型也能读懂。
        if (result.structuredContent() != null) {
      // 序列化成 JSON 后追加，模型能直接读懂这种结构化数据。
            parts.add(toJson(result.structuredContent()));
        }
        // 再处理内容块列表。
        if (result.content() != null) {
            // 逐块处理，因为同一次调用可能返回多段内容。
            for (McpSchema.Content content : result.content()) {
           // 文本块直接取原文，这是最常见也最理想的形态。
                if (content instanceof McpSchema.TextContent textContent) {
  // 文本块原文追加，不做任何加工，避免改变工具本来的输出语义。
                    parts.add(textContent.text());
                } else {
           // 非文本块（图片、资源引用等）序列化成 JSON，至少让模型知道拿到了什么，而不是完全丢掉。
                    parts.add(toJson(content));
                }
            }
        }
      // 什么可读片段都没有时，退化成协议对象自身的字符串形式，保证返回值永远不是空——
        // 否则模型会以为工具没执行，进而重复调用造成额外消耗。
        return parts.isEmpty() ? result.toString() : parts.stream()
                .filter(item -> item != null && !item.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从工具清单快照里解析出工具条目列表。
     *
     * <p>数据流：快照 JSON → 判空 → 反序列化成 Map → 取 tools 字段 → 过滤出 Map 类型的条目 → 返回列表。</p>
     *
     * <p>为什么解析失败也返回空列表而不抛异常：这个方法只服务于「生成工具摘要」和「推断工具名」两件事。
     * 快照坏掉时最坏的结果是模型看不到远程工具清单，会被提示要显式给出工具名——这是可接受的降级；
  * 而抛异常会让整轮对话直接失败，代价大得多。</p>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tools(String schemaJson) {
        // 没有快照（还没测试过）时直接返回空列表，调用方会给出「请先测试」的提示。
        if (schemaJson == null || schemaJson.isBlank()) {
    // 返回不可变空列表，上层据此提示用户先去测试 MCP。
            return List.of();
        }
    // 解析可能因格式问题失败，统一接住降级。
        try {
  // 先整体反序列化成 Map，再取工具清单字段。
            Map<String, Object> schema = objectMapper.readValue(schemaJson, new TypeReference<>() {
            });
       // 取出 tools 字段，它应该是一个条目列表。
            Object tools = schema.get("tools");
        // 只有确实是列表时才继续处理，避免对意外结构做强制转换。
            if (tools instanceof List<?> list) {
       // 只保留 Map 形态的条目，非法条目静默跳过，不因为一条坏数据丢掉整个清单。
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {
            // 快照格式坏掉时降级成空清单：最坏结果只是模型看不到工具列表，不会让整轮对话失败。
            return List.of();
        }
        // tools 字段缺失或不是列表时同样返回空清单。
        return List.of();
    }

    /**
     * 保证传给协议层的参数表永远不是 null。
     *
     * <p>模型完全可能只给工具名不给参数，此时上游传进来的就是 null。
     * 协议层收到 null 会抛出与业务无关的空指针，错误信息毫无指导意义，
     * 因此这里统一兜底成空表，让「无参调用」成为一种正常情形。</p>
     */
    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        // 为空时换成空表，让「不带参数的调用」成为正常情形而不是空指针故障。
        return arguments == null ? new LinkedHashMap<>() : arguments;
    }

    /**
     * 给「拉工具清单失败」挑一个能区分接入方式的错误码。
     *
     * <p>分开统计很有必要：SSE 失败通常是网络或地址问题，stdio 失败通常是命令或环境变量问题，
     * 排查方向完全不同，用同一个码会把两类问题混在一起。</p>
     */
    private String listFailureCode(McpConnectionConfigEntity configuration) {
  // stdio 与 SSE 各给一个码，让监控上能一眼看出失败集中在哪种接入方式。
        return "stdio".equalsIgnoreCase(configuration.getTransportType())
                ? "TOOL_MCP_STDIO_LIST_FAILED" : "TOOL_MCP_SSE_LIST_FAILED";
    }

    /**
     * 给「调用远程工具失败」挑一个能区分接入方式的错误码，理由与拉清单失败相同。
     */
    private String callFailureCode(McpConnectionConfigEntity configuration) {
        // 同样按接入方式分码，便于把网络类失败和进程类失败分开统计。
        return "stdio".equalsIgnoreCase(configuration.getTransportType())
                ? "TOOL_MCP_STDIO_CALL_FAILED" : "TOOL_MCP_SSE_CALL_FAILED";
    }

    /**
     * 把对象转成 JSON 文本，转不了时退化成它的字符串形式。
     *
     * <p>这里故意不抛异常：调用它的地方都是在渲染结果或组装快照，
   * 为了「某个字段序列化不了」而让整次工具调用失败并不值得，退化成可辨认的文本更有用。</p>
     */
    private String toJson(Object value) {
   // 序列化失败时退化为字符串表示，保证调用链不被记录性质的动作打断。
        try {
        // 正常路径：序列化成标准 JSON 文本。
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
       // 序列化不了时退化成对象自身的字符串形式，保证记录性动作不会打断调用链。
            return String.valueOf(value);
        }
    }

    /**
     * 从异常里取一句能给人看的原因，消息为空时退回异常类名。
     *
     * <p>网络类异常经常没有消息文本，直接拼出来会得到「调用失败：null」这种没信息量的提示；
     * 用类名至少能区分是超时、连接被拒还是解析错误。</p>
     */
    private String readableMessage(Exception e) {
        // 取消息，空则用类名兜底。
        String message = e.getMessage();
        // 消息为空时用类名兜底，至少能区分是超时、连接被拒还是解析失败。
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * 把任意对象安全地转成字符串，null 依然返回 null。
     *
* <p>用于从解析出来的 Map 里取值：那些值的类型不受我们控制，直接强制转换会抛类型异常。
     * 保留 null 而不是转成 "null" 字面量，是为了让上层的空值过滤能正常工作。</p>
 */
    private String stringValue(Object value) {
        // 保留 null 不转成 "null" 字面量，这样上层的空值过滤才能正常识别出「没有名字」。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把返回给模型的文本裁剪到长度上限以内。
   *
     * <p>为什么必须裁剪：这段文本会进入下一轮提示词，超长会挤掉对话历史甚至让请求直接失败。
     * 这里选择「直接截断尾部」而不是摘要或分页——截断是确定性的、不引入额外模型调用的最简方案，
     * 代价是尾部内容会丢失，所以上限设得比常见返回值大得多，只兜异常情况。</p>
*/
    private String truncate(String value) {
        // 没超限就原样返回，避免不必要的字符串复制。
        if (value == null || value.length() <= MAX_RESULT_LENGTH) {
   // 原样返回，避免不必要的字符串复制。
            return value;
        }
        // 超限时只保留前面部分，尾部丢弃。
        return value.substring(0, MAX_RESULT_LENGTH);
    }
}
