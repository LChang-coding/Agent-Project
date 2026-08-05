package cn.bugstack.ai.domain.tool.model.valobj;

/**
 * 工具领域所有状态字面量的集合地：既包含工具本身的发布生命周期，也包含一次调用和一次连接测试的结果。
 *
 * <p>所属层次：工具领域的值对象，取值直接对应数据库里 status / test_status 两类列。</p>
 *
 * <p>谁会用它：{@code ToolPublishService} 用 draft/active/disabled 推进发布生命周期，用 untested/success/failed 记录 MCP 连接测试结论；
 * {@code ToolDispatchAuthorizationService} 用 started 落一条「已领到执行权」的审计；
 * {@code ToolGateway} 用 success/failed/started 判断重复调用时该重放什么结果。</p>
 *
 * <p>为什么把三组语义放一起：数据库里这几列都是短字符串，共用一处常量能保证写入和判等永远用同一个字面量，
 * 避免某处写 "SUCCESS"、另一处写 "success" 导致状态判断悄悄失效。</p>
 *
 * <p>它不负责什么：不描述状态之间允许怎么流转（流转规则写在发布服务里），也不做取值校验。</p>
 */
public final class ToolStatus {

    /** 草稿：工具或版本已经建好但没发布，不会出现在任何用户的可调用目录里，模型也看不到它。 */
    public static final String DRAFT = "draft";
    /** 已激活：定义和版本都发布完成，会被可用工具查询捞出来交给模型，是唯一能被真正调用的状态。 */
    public static final String ACTIVE = "active";
    /** 已停用：从运行目录里摘掉，模型下一轮就看不到它了；历史版本和调用审计仍然保留，便于追溯。 */
    public static final String DISABLED = "disabled";
    /** 调用已领到执行权：幂等键刚插库、外部动作即将或正在执行；重试遇到这个状态必须放弃执行，因为无法判断外部副作用有没有发生。 */
    public static final String STARTED = "started";
    /** 失败：既用于 MCP 连接测试没通过，也用于一次工具调用执行报错；失败原因单独存在错误字段里。 */
    public static final String FAILED = "failed";
    /** 成功：MCP 测试拉到了工具清单，或一次工具调用拿到了结果；只有测试成功的 MCP 版本才允许发布。 */
    public static final String SUCCESS = "success";
    /** 尚未测试：MCP 刚创建、还没建过连接；这种版本禁止发布，否则模型会拿到一个连不通的工具反复重试。 */
    public static final String UNTESTED = "untested";

    /** 私有构造：常量集合类，不允许实例化，避免被当成状态机对象误用。 */
    private ToolStatus() {
    }
}
