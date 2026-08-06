package cn.bugstack.ai.domain.run.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次运行（run）的持久化记录，是「取消、引导、恢复、查状态」四件事的唯一依据。
 *
 * <p>解决什么问题：用户点发送后，服务端要跑很久，中途可能换机器、可能被取消、可能被新指令顶替。
 * 只靠内存记不住这些事，所以每次执行都先落一条运行记录，把身份、状态、版本号全写进数据库。
 * 任何节点、任何进程只要拿到 runId，就能从库里读出「这次运行还能不能继续」。</p>
 *
 * <p>所属层次：领域层运行模型，由 {@code RunControlService} 创建和迁移状态，
 * 经 {@code IChatRunRepository} 落库；工具闸门、工作流运行时、SSE 控制器都读它。</p>
 *
 * <p>它不负责什么：不存对话内容（消息在会话领域）、不存节点执行明细（在工作流领域）、
 * 不存模型用量（在用量领域）。它只保存这次执行的「身份 + 状态 + 版本」这三类事实。</p>
 */
@Data
@Builder
public class ChatRunEntity {

    /** 本次运行的业务主键，也是幂等键；前端凭它取消、查状态、查引用，重复提交同一个 runId 不会重复建运行。 */
    private String runId;
    /** 一轮用户意图的编号；一次引导会产生多个 run，但它们共享同一个 turnId，便于把整条引导链看成一轮对话。 */
    private String turnId;
    /** 运行所属租户；所有查询、锁定、状态迁移都带上它做隔离，缺失会导致跨租户读写到别人的运行。 */
    private String tenantId;
    /** 运行所属用户；与租户一起构成可信作用域，防止用别人的 runId 取消或读取他人的运行。 */
    private String userId;
    /** 运行绑定的会话；取消时要按它作废该会话下的消息，加锁时也先锁会话再锁运行以避免死锁。 */
    private String sessionId;
    /** 执行来源类型，取值 agent 或 workflow；决定终结时走哪套收尾逻辑，也用于按来源查活动运行。 */
    private String sourceType;
    /** 实际执行的 Agent 编号或工作流编号；配合来源类型可定位到具体配置，用于判断某工作流是否还有运行在跑。 */
    private String sourceId;
    /** 本轮创建时冻结的 RAG 开关；运行中即使用户改了会话开关也不生效，保证一次运行的检索行为前后一致。 */
    private Boolean ragEnabled;
    /** 本轮冻结的 RAG 策略模式（OFF/AUTO/MANUAL）；与开关一起决定这次要不要检索、按谁的范围检索。 */
    private String ragMode;
    /** 本轮冻结的 RAG 调用方式。 */
    private String ragInvocationMode;
    /** 本轮冻结的会话 RAG 策略版本号；用于事后核对这次运行用的是哪一版检索配置。 */
    private Long ragPolicyRevision;
    /** 本轮冻结的知识库绑定清单；AUTO 模式也在创建时就展开成具体编号，防止运行途中绑定变化导致检索范围漂移。 */
    private List<String> ragBindingIds;
    /** 本轮的根链路编号；模型调用、RAG 检索、工具执行都挂在它下面，出问题时凭它捞出整条链路日志。 */
    private String traceId;
    /** 持久化的运行状态；它是判断「能不能继续执行」和「是否已结束」的唯一权威来源，内存里的任何标记都不作数。 */
    private RunStatus status;
    /** 乐观锁版本号；每次条件更新都要求版本匹配，并发取消、并发完成只会有一个成功，其余得到并发修改错误。 */
    private Integer version;
    /** 创建本轮时看到的会话上下文版本；记录「这次运行是基于哪一版历史起跑的」，用于事后追溯。 */
    private Long baseContextRevision;
    /** 运行继续推理所要求的上下文版本；一旦历史被压缩或被取消作废，这个值会被抬高，
 *  于是所有携带旧版本的工具调用都会被判定为过期并被拒绝，防止模型基于已消失的历史产生副作用。 */
    private Long currentContextRevision;
    /** 引导链中的上一个运行；有值说明本次运行是「用户中途改了要求」后新建出来的后继。 */
    private String predecessorRunId;
  /** 本运行被引导顶替后新建的后继运行；有值即表示已经引导过，重复引导同一指令直接复用它保证幂等。 */
    private String successorRunId;
    /** 与本运行原子绑定的用户消息编号；绑定和写消息在同一个事务里完成，避免出现「有消息没运行」的孤儿数据。 */
    private String userMessageId;
  /** 用户引导时给出的新指令；后继运行启动时要把它合进提示词，否则用户的修改意图就丢了。 */
    private String steerInstruction;
    /** 进入终态的原因文案（完成、失败、取消、被替代）；排查「为什么这次没有回答」时最先看它。 */
    private String terminalReason;
    /** 用户第一次请求取消或引导的时间；用于统计取消响应耗时，也说明这次运行不是自然结束的。 */
    private LocalDateTime cancelRequestedAt;
    /** 真正开始执行模型链路的时间；引导预建的运行此时为空，启动后才补上，耗时统计以它为起点。 */
    private LocalDateTime startedAt;
    /** 进入终态的时间；与开始时间一起算出这次运行总耗时，也是判断「是否有长时间不收敛运行」的依据。 */
    private LocalDateTime finishedAt;
}
