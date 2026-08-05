package cn.bugstack.ai.domain.run.service;

import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.ToolGateDecision;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import org.springframework.stereotype.Service;

/**
 * 工具调用前的统一「闸门」：在模型真的对外界动手之前，把取消、上下文压缩和版本过期三件事一次性检查掉。
 *
 * <p>解决什么问题：模型说要调工具，到工具真的发出 HTTP / MCP 请求之间存在时间差。
 * 这段时间里可能发生两件危险的事——用户点了取消（那就不该再产生任何外部副作用），
 * 或者历史被压缩了（那模型算出来的参数就是基于已经消失的历史，不能信）。
 * 如果每个工具各自去判断，一定会有人漏判，所以收口到这里。</p>
 *
 * <p>所属层次：领域层运行服务，处在「模型输出」与「工具执行」之间。</p>
 *
 * <p>谁会调用它：Agent 的工具回调链路和工作流的工具节点，在拿到模型的工具请求后必须先过这道闸。</p>
 *
 * <p>它向下调用什么：{@code RunControlService} 查数据库里的运行状态与上下文版本；
 * {@code ConversationMemoryService} 在历史过长时执行压缩。</p>
 *
 * <p>它不负责什么：不执行工具本身、不解析工具参数、不落库任何业务数据、不决定用哪个工具。
 * 它只回答「现在可不可以动手」，以及「要不要让模型重来一遍」。</p>
 */
@Service
public class RunExecutionGate {

    /** 运行状态与上下文版本的权威来源；闸门所有放行判断最终都落到它去查数据库，而不是靠内存标记。 */
    private final RunControlService runControlService;
 /** 历史压缩服务；在工具执行前按阈值触发压缩，压缩一旦发生就意味着提示词事实变了，必须让模型重新推理。 */
    private final ConversationMemoryService conversationMemoryService;

    /**
     * 注入运行控制与历史压缩两个依赖。
     *
     * <p>用构造器注入而不是字段注入，是为了让领域单测能直接塞进假的实现来验证放行逻辑。</p>
     */
    public RunExecutionGate(RunControlService runControlService, ConversationMemoryService conversationMemoryService) {
        // 保存运行状态查询入口，后面每次放行判断都要用它。
        this.runControlService = runControlService;
        // 保存压缩入口，工具前的历史裁剪由它执行。
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 模型请求调用工具后、真正执行之前的总检查，决定是放行还是让模型重来。
     *
     * <p>各层职责：
     * 第一层：识别兼容调用。没有 runId 的老链路不参与取消与版本协议，直接放行。
     * 第二层：压缩之前先查一次取消，避免为一个已经被用户停掉的请求白白启动一次压缩计算。
     * 第三层：按阈值尝试压缩历史。压缩一旦发生，模型此前算出的工具参数就基于失效历史，必须抬高版本并让模型重来。
     * 第四层：压缩检查本身也耗时，期间可能刚好收到取消，所以再查一次把竞态窗口封死。</p>
     *
     * <p>数据流：
   * 工具调用上下文
     * → runId 判空
     * → 查库校验状态与上下文版本
     * → 尝试压缩历史
     * → 压缩发生则抬高上下文版本并返回「重新推理」
 * → 未压缩则再次校验状态
     * → 返回「放行」</p>
     *
     * <p>会写数据库：压缩本身会落库，抬高上下文版本也会改运行记录。
     * 主要失败条件：运行已取消 / 已被引导替代 / 已结束，或携带的上下文版本已过期——
     * 这两种情况都由下层直接抛业务异常中断整条工具链，不会走到返回值。</p>
     *
     * @param context             本次工具调用的运行上下文，携带租户、用户、会话、运行和上下文版本
     * @param visibleThroughSequence 压缩时允许看到的历史截止位置，防止把还没确认的消息一起压进去
     * @return ALLOW 表示可以继续执行工具；RETRY_MODEL 表示丢弃本次工具请求并让模型基于新历史重新推理
     */
    public ToolGateDecision beforeTool(ToolInvokeContextEntity context, Integer visibleThroughSequence) {
        // 第一层：老链路可能根本没有运行记录，这类调用无法参与取消与版本协议，只能直接放行。
        if (context == null || blank(context.getRunId())) {
            // 无 run 的兼容调用不参与取消与上下文版本协议。
            return ToolGateDecision.ALLOW;
        }
      // 第二层：压缩前先查取消，避免为已终止请求启动额外压缩任务。
        runControlService.requireExecutable(context.getTenantId(), context.getUserId(), context.getRunId(),
                context.getContextRevision());
  // 第三层：按阈值尝试裁剪历史；返回 true 说明这次真的压缩了，历史事实已经改变。
        boolean compacted = conversationMemoryService.compactBeforeTool(context.getTenantId(), context.getUserId(),
                context.getSessionId(), context.getRunId(), visibleThroughSequence, context.getTraceId());
        // 压缩发生后不能沿用旧参数，必须让模型看新历史重来一遍。
        if (compacted) {
    // 压缩改变提示词事实，旧模型产生的工具参数必须丢弃并重新推理。
            runControlService.refreshContextRevision(context.getTenantId(), context.getUserId(), context.getRunId());
            // 通知调用方作废本次工具请求，重新走一轮模型推理。
            return ToolGateDecision.RETRY_MODEL;
        }
        // 第四层：压缩检查期间可能收到取消，再检查一次封闭竞态窗口。
        runControlService.requireExecutable(context.getTenantId(), context.getUserId(), context.getRunId(),
                context.getContextRevision());
   // 状态和版本都没问题，允许工具继续执行。
        return ToolGateDecision.ALLOW;
    }

    /**
     * 真正把请求发给外部系统（HTTP / MCP）之前的最后一道确认。
     *
     * <p>为什么还要再查一次：{@link #beforeTool} 用的是最多 200 毫秒有效期的只读快照，速度快但可能读到瞬间过期的状态。
     * 而外部副作用是不可撤销的——邮件发出去就收不回来。所以这里改成直接锁数据库行读取，
     * 把「用户已经取消但缓存还没失效」这个窗口彻底关掉。</p>
     *
     * <p>会开事务并加行锁。运行已取消或上下文版本过期时直接抛业务异常，外部请求根本不会发出。</p>
     */
    public void beforeDispatch(ToolInvokeContextEntity context) {
        // 没有运行记录的兼容调用不做门禁，其余情况一律以数据库加锁读为准。
        if (context != null && !blank(context.getRunId())) {
         // 真正发 HTTP/MCP 前锁数据库授权，确保取消不会穿透缓存窗口。
            runControlService.authorizeToolDispatch(context.getTenantId(), context.getUserId(), context.getRunId(),
                    context.getContextRevision());
        }
    }

    /**
     * 读取这次工具调用所属运行的最新记录。
     *
     * <p>工具执行过程中若需要用到运行上固化的信息（例如本轮冻结的 RAG 绑定、traceId），
     * 必须从这里现取，不能用启动时缓存的旧副本，否则引导替换后会用错配置。</p>
     *
     * <p>运行不存在或不属于当前租户与用户时，下层会抛业务异常，不会返回 null。</p>
  */
    public ChatRunEntity currentRun(ToolInvokeContextEntity context) {
        // 直接向运行控制服务要一份权威记录，找不到即视为无权访问并抛异常。
        return runControlService.require(context.getTenantId(), context.getUserId(), context.getRunId());
    }

    /** 判断运行标识是否缺失；缺失代表这是不参与取消协议的兼容调用，而不是数据异常。 */
    private boolean blank(String value) {
        // 空串和纯空白都算没有，避免把空字符串当成合法 runId 去查库。
        return value == null || value.isBlank();
    }
}
