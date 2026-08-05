package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolDispatchClaimEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.model.valobj.ToolStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 工具产生外部副作用之前的那道门禁：确认这次运行还该继续，并保证同一件事只被执行一次。
 *
 * <p>所属层次：领域层服务。它是「取消一次运行」和「工具真的去调外部系统」这两件事之间的仲裁者。</p>
 *
 * <p>谁调用它：只有 {@code ToolGateway}。网关在真正调用工具之前必须先向它领执行权，
 * 执行完再回来把结果写进审计。</p>
 *
 * <p>它向下调用什么：{@code RunControlService}（给运行加数据库行锁并校验状态与上下文版本）、
 * 工具仓储（用幂等键抢锁式插入 started 日志、事后回填终态）。</p>
 *
 * <p>为什么必须在一个短事务里做完：用户点「停止」和模型发起工具调用是两个并发动作。
 * 如果先读状态、再执行、再写日志，中间就有窗口——用户已经取消了，工具却照样把订单下出去。
 * 把「锁运行 + 抢幂等键」放进同一个事务，等于用数据库给这两个动作排出了确定的先后顺序。
 * 事务要短，是因为它持有行锁，长事务会把同一次运行的其他操作全都堵住。</p>
 *
 * <p>它不负责什么：不执行工具、不认识 Skill 和 MCP 的差别、不裁剪结果、不做权限判断
 * （能不能用这个工具在解析阶段已经定了）。</p>
 */
@Service
public class ToolDispatchAuthorizationService {

    /**
  * 运行控制服务。这里只用它一个能力：给运行加行锁，并检查运行是否仍可执行、上下文版本是否还对得上。
  *
     * <p>为什么必须加锁而不是读缓存：缓存里的状态可能是几百毫秒前的快照，
     * 而用户的取消动作恰好可能落在这几百毫秒里。外部副作用不可撤销，只能用行锁换确定性。</p>
     */
    private final RunControlService runControlService;
    /**
     * 工具仓储。用它做两件事：用幂等键做「插入即加锁」抢执行权，以及事后把调用结果回填成终态。
     *
     * <p>这两步都必须落库：内存里的标记进程一重启就没了，而外部副作用是真实发生过的。</p>
     */
    private final IToolRepository toolRepository;

    /**
     * 注入运行控制服务和工具仓储，完成构造。
     *
     * <p>只做依赖装配，不持有任何可变状态，因此本服务是无状态且线程安全的——
     * 并发安全完全依赖数据库的行锁和唯一索引，而不是 Java 层的同步。</p>
     */
    public ToolDispatchAuthorizationService(RunControlService runControlService, IToolRepository toolRepository) {
        this.runControlService = runControlService;
        this.toolRepository = toolRepository;
    }

    /**
     * 领取一次「可以调用外部工具」的执行权。
     *
     * <p>各层职责：
     * 第一层：如果这次调用挂在某个运行上，先给运行加行锁，确认它没被取消、没被替代，
     *         并且模型做决定时看到的上下文版本和当前一致；任一条不满足就抛异常，工具根本不会被调用。
     * 第二层：算出幂等键。有运行编号和模型函数调用编号时用它们哈希出一个可重现的键，
     *      这样模型重试同一个函数调用会算出同一个键。
     * 第三层：以 started 状态尝试把审计日志插进库。插入成功即代表抢到执行权。
     * 第四层：插入冲突说明别人已经抢过，把已存在的那条日志查出来交给调用方去重放。</p>
     *
     * <p>数据流：
     * 工具目录项 + 调用上下文 + 入参 JSON
   * → 锁运行并校验状态与上下文版本
  * → 计算幂等键
 * → 插入 started 审计（唯一索引即锁）
     * → 插入成功则返回「已领到执行权」
     * → 插入冲突则查出既有日志返回「未领到执行权」</p>
     *
     * <p>会写库、会加行锁，整个方法在一个事务里。失败情形：运行不存在或无权访问、运行已取消或结束、
     * 上下文版本过期、抢锁失败且又查不到既有记录（此时宁可报错让模型重新推理，也不冒险执行第二遍）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolDispatchClaimEntity claim(ToolCatalogEntity tool, ToolInvokeContextEntity context, String inputJson) {
        // 第一层：只有挂在运行上的调用才需要校验运行状态；没有运行编号的独立调用（例如内部脚本触发）跳过这一步。
        if (!blank(context.getRunId())) {
   // 行锁确定取消与工具副作用的先后顺序，并校验上下文 revision。
            runControlService.authorizeToolDispatch(context.getTenantId(), context.getUserId(), context.getRunId(),
                    context.getContextRevision());
        }
        // 第二层：算出这次调用的幂等键，它决定了「重试会不会被识别成同一件事」。
        String idempotencyKey = buildIdempotencyKey(tool, context);
     // 第三层：先把审计日志按 started 状态组装好；它既是审计记录，也是抢执行权用的那把锁。
        ToolCallLogEntity log = ToolCallLogEntity.builder()
                .tenantId(context.getTenantId()).userId(context.getUserId()).sessionId(context.getSessionId())
                .runId(context.getRunId()).workflowId(context.getWorkflowId()).toolType(tool.getToolType())
                .toolId(tool.getToolId()).toolName(tool.getToolName()).version(tool.getVersion())
                .invocationId(defaultString(context.getInvocationId(), "tool_inv_" + UUID.randomUUID()))
                .functionCallId(context.getFunctionCallId()).idempotencyKey(idempotencyKey)
                .traceId(context.getTraceId()).inputJson(inputJson).status(ToolStatus.STARTED)
                .startedAt(LocalDateTime.now()).build();
        // 用「插入即加锁」的方式抢执行权：影响行数为 1 说明这条幂等键是本次请求第一次写入。
        if (toolRepository.claimToolCallLog(log) == 1) {
      // 唯一索引插入成功者才允许继续外部调用。
            return ToolDispatchClaimEntity.builder().claimed(true).callLog(log).build();
        }
        // 第四层：插入冲突说明这次调用此前已经被处理过，把那条既有记录查出来供调用方重放结果。
        ToolCallLogEntity existing = toolRepository.queryToolCallLogByIdempotencyKey(idempotencyKey);
        // 抢锁失败却又查不到记录属于异常情况（例如刚好被清理），此时宁可让模型重新推理，也不冒险再执行一次。
        if (existing == null) {
            throw new AppException("TOOL_CALL_CLAIM_FAILED", "工具调用权领取失败，请重新推理");
        }
  // 明确告知调用方「你没抢到」，并把既有日志一起带回去决定重放什么内容。
        return ToolDispatchClaimEntity.builder().claimed(false).callLog(existing).build();
    }

    /**
     * 把一条 started 审计推进到终态，写入输出、错误信息和耗时。
 *
     * <p>输入是当初领到执行权的那条日志（提供幂等键）以及本次执行结果；无返回值。</p>
     *
     * <p>会写库，在自己的事务里。影响行数不等于 1 就抛异常——说明这条记录没找到或已被别人改过，
   * 这属于必须暴露的审计异常，不能静默忽略。</p>
     *
     * <p>为什么这一步很关键：只有终态落库，后续重试才能读到确定结果并安全重放。
     * 长期停留在 started 的记录会让所有重试都得到「结果未知」，从而永远拒绝执行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(ToolCallLogEntity log, String outputJson, String status, String errorType,
                       String errorMessage, Long costMs) {
     // 按幂等键定位那条 started 记录并推进终态；影响行数不为 1 说明记录不存在或已被并发改写。
        if (toolRepository.finishToolCallLog(log.getIdempotencyKey(), outputJson, status,
                errorType, errorMessage, costMs) != 1) {
   // 审计写失败必须暴露出来，否则会留下永远处于 started 的僵尸记录，导致后续重试全部被拒。
            throw new AppException("TOOL_CALL_LOG_FINISH_FAILED", "工具调用结果审计更新失败");
        }
    }

    /**
     * 算出这次调用的幂等键，也就是判断「两次请求是不是同一件事」的标准。
     *
     * <p>有运行编号和模型函数调用编号时，把租户、用户、运行、函数调用、工具五项拼起来做哈希。
     * 这个键是可重现的：模型重试同一个函数调用会算出完全一样的键，从而在插库时撞上唯一索引被拦下。</p>
     *
     * <p>缺少这两者时只能退化成随机键，等于不做幂等保护——因为没有任何稳定标识能说明「这是同一次调用」。
     * 这是有意的取舍：随机键至少保证审计记录不丢，而不是干脆拒绝执行。</p>
     *
   * <p>为什么要哈希而不是直接拼接：拼出来的原文里含租户和用户编号，会成为库中可见的身份信息，
     * 而且长度不可控容易超出索引列长度。哈希既定长又不直接暴露身份。</p>
     */
    private String buildIdempotencyKey(ToolCatalogEntity tool, ToolInvokeContextEntity context) {
        // 只有运行编号和函数调用编号同时具备，才能算出可重现的稳定键。
        if (!blank(context.getRunId()) && !blank(context.getFunctionCallId())) {
 // 五个维度拼成源串：少任何一项都可能让两次本不相同的调用被误判成同一件事。
            String source = context.getTenantId() + ':' + context.getUserId() + ':' + context.getRunId() + ':'
                    + context.getFunctionCallId() + ':' + tool.getToolId();
            // 加前缀便于人工识别键的来源，哈希值保证定长且不直接暴露身份信息。
            return "tool_call_sha256_" + sha256(source);
        }
      // 缺少稳定标识时退化为随机键：审计仍然会记录，但这次调用不受幂等保护。
        return "tool_call_" + UUID.randomUUID();
    }

    /**
     * 把一段文本算成 SHA-256 的十六进制字符串。
     *
     * <p>只用于生成幂等键，不用于任何加密或鉴权场景。</p>
     *
     * <p>算法不可用（理论上不会发生）时抛业务异常而不是静默换算法：
   * 幂等键算错等于幂等保护失效，用户可能被重复扣费，宁可这次调用直接失败。</p>
     */
    private String sha256(String source) {
        // 摘要算法缺失会抛受检异常，必须接住并翻译成业务异常。
        try {
            // 固定用 UTF-8 编码取字节，避免不同环境默认字符集不同导致同一段文本算出不同哈希。
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
// 转成十六进制字符串，得到定长、可作为索引列的键值。
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
   // 算不出键就等于失去幂等保护，此时必须让整次调用失败，不能带着随机键继续。
            throw new AppException("TOOL_CALL_KEY_FAILED", "工具调用幂等键生成失败", e);
        }
    }

    /**
 * 取第一个有值的字符串，用于给可选字段补默认值。
     *
     * <p>当前只服务于「推理调用编号」：上下文里没带时会现场生成一个，
     * 保证审计表里这一列永远有值，便于把同一轮推理产生的多次工具调用聚在一起看。</p>
     */
    private String defaultString(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    /**
  * 判断一个字段是否等于没有值（空引用或全是空白字符）。
     *
     * <p>连空白串也算空很重要：如果空串被当成有效的运行编号，
     * 就会去锁一个不存在的运行，或者算出一个看似稳定其实毫无意义的幂等键。</p>
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
