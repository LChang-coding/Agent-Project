package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次工具调用的持久化档案：既是事后审计，也是「同一次调用不许执行两遍」的凭据。
 *
 * <p>所属层次：工具领域的实体，对应工具调用日志表，会真正落库。</p>
 *
 * <p>生命周期：领执行权时以 started 状态插入（靠幂等键唯一索引抢锁），外部工具跑完后回填成 success 或 failed。
 * 也就是说这张表里的一条记录同时承担两件事——记录发生了什么，以及占住「这件事已经有人在做」的位置。</p>
 *
 * <p>谁写它：{@code ToolDispatchAuthorizationService}（插入 started、回填终态）。</p>
 *
 * <p>谁读它：{@code ToolGateway} 在幂等冲突时读它决定重放什么结果；发布服务的会话调用日志查询读它给前端展示。</p>
 *
 * <p>它不负责什么：不存工具配置、不存凭证；输入输出只存审计用的 JSON 摘要，不是完整的请求响应报文。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLogEntity {

    /** 数据库自增主键；只在库内使用，不对外暴露，也不参与任何业务判断。 */
    private Long id;
    /** 这次调用所属租户；查询日志时必须带上它做隔离，否则会把别的公司的调用记录查出来。 */
    private String tenantId;
    /** 发起调用的可信用户；调用出问题时的责任人，也是查询日志的过滤条件之一。 */
    private String userId;
    /** 关联的业务会话；前端按会话拉取本次对话用过哪些工具，靠的就是这个字段。 */
    private String sessionId;
    /** 关联的权威运行；配合函数调用编号生成幂等键，也是「取消运行后不许再产生副作用」这条规则的锚点。 */
    private String runId;
    /** 工作流调用时是目标工作流编号，普通 Agent 调用时可以为空；用于区分这次工具是被谁触发的。 */
    private String workflowId;
    /** 工具类型（skill 或 mcp）；查询和统计时用它区分两类调用的成功率与耗时特征。 */
    private String toolType;
    /** 工具稳定业务编号；统计「用了几个不同工具」时按它去重，也是幂等键的组成部分之一。 */
    private String toolId;
    /** 调用当时的工具名称快照；工具后来改名或停用了，这条历史记录仍能显示当时用的是什么，不会变成一串编号。 */
    private String toolName;
    /** 调用时冻结的版本号；同一个工具不同版本行为可能完全不同，排查历史问题必须知道当时跑的是哪一版。 */
    private String version;
    /** ADK 本次推理的调用编号；上下文里没给时会现场生成一个，保证这一列永远有值，便于把同一轮的多次工具调用聚在一起。 */
    private String invocationId;
    /** 大模型这次函数调用的编号；它是幂等键的核心输入，模型重试同一个函数调用时会算出同一个键从而被拦下。 */
    private String functionCallId;
    /**
     * 幂等键，数据库上有唯一索引。插入成功即代表抢到了执行权，插入冲突即代表这次调用已经有人做过。
     * 它是防止重复下单、重复扣费这类真实损失的最后一道闸；有运行和函数调用编号时由它们哈希得出（可重现），
     * 缺失时退化为随机键（等价于不做幂等）。
     */
    private String idempotencyKey;
    /** 入口全链路追踪编号；把这条工具日志和同一次请求的其他日志串起来，是线上定位问题的主要抓手。 */
    private String traceId;
    /** 序列化后的入参 JSON，用于审计和复现问题；序列化失败时会退化成空对象，不会因为记日志失败而阻断调用。 */
    private String inputJson;
    /** 序列化后的出参 JSON；重复调用时直接从这里取结果重放给模型，避免再产生一次外部副作用。 */
    private String outputJson;
    /** 调用状态（started/success/failed）；started 是「已开始但结果未知」的中间态，重试遇到它必须放弃执行而不是猜结果。 */
    private String status;
    /** 抢到执行权的时刻；配合耗时可以还原这次外部调用的时间窗，也用于清理长期挂在 started 的僵尸记录。 */
    private LocalDateTime startedAt;
    /** 异常类型短码（通常是异常类名）；便于按错误类型聚合统计，判断是超时多还是参数错多。 */
    private String errorType;
 /** 截断后的错误摘要；只保留可对外的文案，不写堆栈和内部细节，因为这段文字可能被重放给大模型看到。 */
    private String errorMessage;
    /** 工具执行耗时（毫秒）；用于发现慢工具，也是判断是否该调整超时配置的依据。 */
    private Long costMs;
    /** 预留的扩展元数据 JSON；当前流程不写，留给后续补充自定义审计字段而不必改表结构。 */
    private String metadata;
    /** 记录创建时间，由数据库或仓储填充；用于按时间范围检索历史调用。 */
    private LocalDateTime createTime;
}
