package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.ToolGateDecision;
import cn.bugstack.ai.domain.run.service.RunExecutionGate;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolRuntimeContextKeys;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用前的安全门禁：确认这次运行还活着、上下文版本还有效，才允许工具真正执行。
 *
 * <p>解决什么问题：工具会产生真实的外部副作用（写数据、发消息、花钱）。用户点了「停止」之后，
 * 模型可能还在往下推理并触发工具调用。如果没有这道门禁，取消就形同虚设。</p>
 *
 * <p>所属层次：领域层的装配辅料（ADK 插件）。</p>
 *
 * <p>谁会调用它：ADK 在每次工具调用前回调它；{@code RunnerNode} 会强制把它挂进每个 Runner，
 * 不依赖配置是否写了它。</p>
 *
 * <p>它向下调用什么：{@code RunExecutionGate} 读取权威运行状态，并在需要时执行调用前的上下文压缩。</p>
 *
 * <p>它不负责什么：不执行工具、不校验工具参数是否合理、不做限流。
 * 它只回答一个问题：这次工具调用现在还该不该发生。</p>
 *
 * <p>关键设计：所有身份信息只从 ADK 的可信 state 里取，绝不读模型生成的工具参数。
 * 否则模型只要在参数里编一个别人的 runId，就能绕过门禁。</p>
 */
@Slf4j
@Service("toolExecutionGuardPlugin")
public class ToolExecutionGuardPlugin extends BasePlugin {

    /**
     * 运行门禁服务：读取数据库里的权威运行状态，必要时完成调用前的上下文压缩。
     *
     * <p>为什么要读数据库而不是内存：取消请求可能打到集群里的另一台机器上，
     * 只有数据库里的状态才是所有实例都认的事实。</p>
     */
    private final RunExecutionGate runExecutionGate;

    /**
     * 注入门禁服务，并把插件名固定下来。
     *
     * <p>名字写死是必要的：{@code RunnerNode} 靠它判断这个插件是否已经挂过，避免重复挂载
     * 导致每次工具调用都做两遍状态查询。</p>
     */
    public ToolExecutionGuardPlugin(RunExecutionGate runExecutionGate) {
        // 用固定插件名注册，供 Runner 去重识别。
        super("toolExecutionGuardPlugin");
        // 保存门禁服务，每次工具调用前都要用它查状态。
        this.runExecutionGate = runExecutionGate;
    }

    /**
     * 工具执行前的拦截点：放行、要求重新推理、或直接阻断。
     *
     * <p>各层职责：
     * 第一层：没有 ADK 运行上下文的调用（启动自检、独立测试）不在治理范围，直接放行。
     * 第二层：state 里没有 runId 说明这次调用不属于任何业务运行，同样放行以兼容非会话场景。
     * 第三层：只用可信 state 拼出门禁上下文，绝不读模型生成的工具参数。
     * 第四层：向门禁询问决定；放行就返回空让工具照常执行。
     * 第五层：门禁说上下文被压缩过，就把新版本写回 state，并返回一个「请重新推理」的结构化结果，
     *         阻止这次工具调用——因为模型是基于旧上下文决定调这个工具的，前提已经变了。
     * 第六层：业务异常（已取消、版本冲突、身份不符）转成模型能读懂的阻断结果，工具不执行。
     * 第七层：未知故障也阻断——宁可拒绝一次合法调用，也不能冒险产生不该有的外部副作用。</p>
     *
     * <p>数据流：
     * 工具调用请求
     * → 检查 ADK 上下文与 runId
     * → 从可信 state 拼出门禁上下文（租户/用户/会话/运行/上下文版本/链路）
     * → 询问运行门禁
     * → 放行：返回空 → ADK 继续执行真实工具
     * → 需刷新：更新 state 里的上下文版本 → 返回重试提示 → 模型基于新上下文重新决策
     * → 拒绝：返回阻断结果 → 工具不执行，模型看到失败原因</p>
     *
     * <p>返回空表示放行，返回内容表示短路——短路时 ADK 会把返回的 Map 当成工具执行结果交给模型，
     * 所以阻断结果必须是模型能理解的结构，而不是抛异常把整个对话打断。</p>
     */
    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs,
                                                          ToolContext toolContext) {
        // 第一层：无 ADK 运行上下文的非会话工具不在本插件治理范围内。
        if (toolContext == null || toolContext.state() == null) {
            // 返回空即放行，让工具照常执行。
            return Maybe.empty();
        }
        // 取出 ADK 的可信状态表，所有身份信息都从这里读。
        Map<String, Object> state = toolContext.state();
        // 运行编号是查权威状态的钥匙。
        String runId = stringValue(state.get(ToolRuntimeContextKeys.RUN_ID));
        // 第二层：兼容启动期或独立测试调用；会话工具必须由 ChatService 注入 runId。
        if (blank(runId)) {
            // 没有运行归属就无从判断取消状态，放行。
            return Maybe.empty();
        }
        // 第三层：只用可信 state 构造门禁上下文，不读取模型生成的 toolArgs。
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder()
                .tenantId(stringValue(state.get(ToolRuntimeContextKeys.TENANT_ID)))
                .userId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.USER_ID)), toolContext.userId()))
                .sessionId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.SESSION_ID)), toolContext.sessionId()))
                .workflowId(stringValue(state.get(ToolRuntimeContextKeys.WORKFLOW_ID)))
                .invocationId(toolContext.invocationId())
                .runId(runId)
                .contextRevision(longValue(state.get(ToolRuntimeContextKeys.CONTEXT_REVISION)))
                .functionCallId(toolContext.functionCallId().orElse(null))
                .traceId(defaultString(stringValue(state.get(ToolRuntimeContextKeys.TRACE_ID)), TraceContext.currentOrNewTraceId()))
                .build();
        // 门禁查询本身可能失败，所有异常都要转成阻断结果而不是抛出去打断对话。
        try {
            // 第四层：询问门禁——这次工具调用现在还该不该发生。
            ToolGateDecision decision = runExecutionGate.beforeTool(context,
                    integerValue(state.get(ToolRuntimeContextKeys.CONTEXT_VISIBLE_THROUGH_SEQUENCE)));
            // 明确放行就返回空，让 ADK 执行真实工具。
            if (decision == ToolGateDecision.ALLOW) {
                // 返回空即放行。
                return Maybe.empty();
            }
            // 第五层：调用前压缩改变了上下文版本：阻止本次工具，要求模型基于新上下文重新推理。
            ChatRunEntity currentRun = runExecutionGate.currentRun(context);
            // 把新版本号写回 state，模型重新推理时用的就是压缩后的上下文。
            state.put(ToolRuntimeContextKeys.CONTEXT_REVISION, currentRun.getCurrentContextRevision());
            // 返回可重试的阻断结果，模型会读懂并重新决策。
            return Maybe.just(blockedResult("RUN_CONTEXT_REFRESH_REQUIRED",
                    "上下文已安全压缩，请基于最新上下文重新推理后再决定是否调用工具", true));
        } catch (AppException e) {
            // 第六层：取消、版本冲突和身份错误都转成模型可理解的阻断结果，不执行工具。
            log.info("工具调用被运行闸门拦截 runId:{} tool:{} code:{}",
                    runId, tool == null ? null : tool.name(), e.getCode());
            // 标记为不可重试：这类原因不会因为再试一次而改变。
            return Maybe.just(blockedResult(e.getCode(), safeMessage(e), false));
        } catch (Exception e) {
            // 第七层：未知门禁故障失败关闭，宁可拒绝也不冒险产生外部副作用。
            log.error("工具调用前守卫异常，已失败关闭 runId:{} tool:{}",
                    runId, tool == null ? null : tool.name(), e);
            // 同样标记不可重试，避免模型陷入反复重试的循环。
            return Maybe.just(blockedResult("TOOL_GATE_FAILED", "工具调用前安全检查失败，已拒绝执行", false));
        }
    }

    /**
     * 组装一个模型能读懂的「工具被拦截」结果。
     *
     * <p>为什么要固定这套字段：模型看到的是工具返回值，必须一眼能分辨「工具执行失败」和
     * 「工具被系统拦截」，以及「值不值得重试」。字段名混乱会让模型胡乱重试或误报成功。</p>
     *
     * <p>用有序 Map 是为了让输出的 JSON 字段顺序稳定，便于日志比对和排查。</p>
     *
     * <p>错误码和文案都做了空值兜底，保证模型永远拿到有意义的说明而不是 null。</p>
     */
    private Map<String, Object> blockedResult(String code, String message, boolean retryRequired) {
        // 用有序 Map 保证序列化后的字段顺序稳定。
        Map<String, Object> result = new LinkedHashMap<>();
        // 明确告诉模型这次工具没有成功。
        result.put("success", false);
        // 区分「工具自己失败」和「被系统拦截」，两者的应对方式不同。
        result.put("blocked", true);
        // 告诉模型这次拦截是否值得重试，避免无意义的反复调用。
        result.put("retryRequired", retryRequired);
        // 错误码，缺失时给一个通用码，保证字段不为空。
        result.put("code", defaultString(code, "TOOL_EXECUTION_BLOCKED"));
        // 人和模型都能看懂的原因说明，缺失时给通用文案。
        result.put("error", defaultString(message, "工具调用已被拦截"));
        // 返回结构化阻断结果，ADK 会把它当作工具执行结果交给模型。
        return result;
    }

    /**
     * 取出异常里可以展示给模型的原因文本。
     *
     * <p>领域异常自带的说明比通用文案有用得多（比如「运行已取消」vs「工具调用已被拦截」），
     * 所以优先用它；只有确实没有说明时才退回通用文案。</p>
     */
    private String safeMessage(Exception e) {
        // 异常没带说明就用通用文案，避免给模型一个 null。
        return e.getMessage() == null || e.getMessage().isBlank() ? "工具调用已被拦截" : e.getMessage();
    }

    /**
     * 把 state 里的任意值安全地转成字符串。
     *
     * <p>state 是弱类型的 Map，取出来的可能是任何类型。统一转字符串，空值保持为空，
     * 避免出现 "null" 这种被当成有效身份的字符串。</p>
     */
    private String stringValue(Object value) {
        // 空值保持为空，不要变成字符串 "null"。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把 state 里的上下文版本号解析成长整型。
     *
     * <p>先按数字类型直接取，再退回字符串解析，兼容 state 在序列化往返后类型变化的情况。</p>
     *
     * <p>解析失败返回空，交给门禁按「没提供版本」处理，而不是当成 0——
     * 0 是一个有效版本号，会让版本校验做出错误判断。</p>
     */
    private Long longValue(Object value) {
        // 已经是数字类型就直接转，最常见的情况。
        if (value instanceof Number number) {
            // 统一转成长整型返回。
            return number.longValue();
        }
        // 不是数字就尝试按字符串解析，兼容序列化往返后的类型变化。
        try {
            // 空值保持为空；非空则解析。
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            // 非法值交由门禁按缺失处理，绝不擅自当成 0。
            return null;
        }
    }

    /**
     * 把 state 里的可见消息序号解析成整型。
     *
     * <p>思路同版本号解析：先按数字取，再按字符串解析，失败返回空按缺失处理。
     * 这个序号决定模型能看到哪些历史消息，猜错会导致上下文范围出错。</p>
     */
    private Integer integerValue(Object value) {
        // 已经是数字类型就直接转。
        if (value instanceof Number number) {
            // 统一转成整型返回。
            return number.intValue();
        }
        // 否则尝试按字符串解析。
        try {
            // 空值保持为空；非空则解析。
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            // 非法值交由门禁按缺失处理，不擅自取默认值。
            return null;
        }
    }

    /**
     * 主值为空时退回备用值。
     *
     * <p>用在用户和会话这两个身份字段上：优先用 ChatService 注入的可信值，
     * 缺失时退回 ADK 自己带的值，保证门禁至少有身份可查。</p>
     */
    private String defaultString(String value, String defaultValue) {
        // 主值为空或纯空白就用备用值。
        return blank(value) ? defaultValue : value;
    }

    /**
     * 统一判断字符串是否「没有内容」。
     *
     * <p>把 null 和纯空白当成同一件事：一个只有空格的 runId 和没有 runId 一样不可用，
     * 分开判断只会让每个调用点都写两遍条件。</p>
     */
    private boolean blank(String value) {
        // 空值和纯空白都算没有内容。
        return value == null || value.isBlank();
    }
}
