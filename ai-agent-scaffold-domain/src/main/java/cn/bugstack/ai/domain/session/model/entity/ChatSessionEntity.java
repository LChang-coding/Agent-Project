package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一段对话会话的档案：这段对话属于谁、跑的是哪个 Agent 还是哪条工作流、上下文改过几次。
 *
 * <p>所属层次：领域层（domain）session 子域的聚合根，对应数据库表 {@code chat_session}。
 * 会话是所有对话数据的归属根——消息、运行记录、附件、分享、上下文摘要全部挂在 sessionId 下面。</p>
 *
 * <p>谁会用它：{@code SessionDomain} 创建和校验它，{@code SessionLifecycleService} 删除它，
 * 对话流程读它来确定这轮该用什么 Agent、什么模型、要不要开检索；控制器层转成 DTO 给前端。</p>
 *
 * <p>它不负责什么：不含消息正文（消息在 {@code ChatMessageEntity} 里）、不含运行状态、
 * 不做权限判断（判断在 {@code SessionDomain} 和 SQL 条件里）、不自行修改字段落库
 * （状态变更一律走仓储的条件 UPDATE，这样并发下才有唯一胜者）。</p>
 */
@Data
@Builder
public class ChatSessionEntity {

    /**
     * 会话所属租户；数据隔离的第一道条件，个人模式为 null。
     *
     * <p>所有按会话查询的 SQL 都带它，SQL 里用「都为空或相等」的写法匹配，
     * 所以不能把空串当 null 传下去，否则个人模式的会话一条都查不到。</p>
   */
    private String tenantId;

    /**
     * 会话所属用户；判断「这段对话是不是你的」唯一依据。
   *
     * <p>值必须来自认证上下文。所有读写会话和消息的 SQL 都把它写进 WHERE，
     * 因此别人即使知道 sessionId 也查不出内容——这是防越权的基础。</p>
  */
    private String userId;

    /**
     * 会话业务标识，前端每轮对话都要原样带回来。
     *
     * <p>它是消息、运行、附件、分享的挂载点。前端一旦丢了它，下一轮就变成新会话，历史全断。</p>
     */
    private String sessionId;

    /**
     * 这段会话要跑的 Agent 编号；工作流会话里放的是工作流的根 Agent。
     *
     * <p>校验会话访问时如果调用方额外指定了 agentId，会和这个值比对，不一致直接拒绝，
   * 防止用 A 智能体的会话去跑 B 智能体从而绕过配置限制。</p>
     */
private String agentId;

    /**
     * 运行目标的展示名，只用于界面显示和会话标题兜底，不参与任何判断。
     */
    private String agentName;

    /**
     * 会话类型：agent 表示单个智能体，workflow 表示跑数据库里配置的工作流。
     *
     * <p>它决定后续发消息时走哪套执行路径，创建时确定后就不再变，避免中途换轨导致历史无法解释。</p>
     */
    private String sourceType;

    /**
     * 工作流会话固化下来的发布版本号；Agent 会话为空。
     *
  * <p>固化的意义是：工作流之后被改了或发了新版，这段已有会话仍按当初那版执行，
     * 否则同一段对话前后行为不一致，用户和排查人员都无法解释。</p>
     */
    private Integer workflowVersion;

    /**
     * 工作流会话固化下来的模型编号；Agent 会话为空。
  *
     * <p>和版本号同理：模型换了会显著改变回答风格和能力，所以在会话上锁定一次。</p>
     */
    private String modelCode;

    /**
     * 应用展示名，用于界面分组和日志标注，不参与业务判断。
     */
    private String appName;

    /**
     * 会话标题，用于会话列表显示；创建时若没给就退回用 Agent 名。
     */
    private String title;

    /**
     * 会话生命周期状态：active 可用，deleted 已软删。
     *
     * <p>软删而不是真删，因为消息、分享、上下文摘要都挂在它下面，真删会造成大量悬空引用。
     * 删除后所有查询都读不到它，等于从用户视角消失。</p>
     */
    private String status;

    /**
     * 后续这段会话运行时要不要开启知识库检索。
     *
     * <p>新建会话一律为 false，刻意不继承任何客户端或浏览器缓存里的状态，
     * 避免用户在别处开过检索就悄悄影响这段新对话的成本和结果。</p>
     */
    private Boolean ragEnabled;

    /**
     * 检索模式：OFF 不检索、AUTO 自动判断、MANUAL 用户手工指定知识库。
     *
     * <p>比单纯的开关表达力更强，是实际生效的策略；上面那个布尔字段只是给旧接口的兼容视图。</p>
     */
    private String ragMode;

    /** RAG 调用方式：AUTO_CONTEXT 自动注入，AGENT_TOOL 由 Agent 显式调用。 */
    private String ragInvocationMode;

    /**
     * 检索策略的乐观锁版本号。
     *
     * <p>用来防「两个标签页互相覆盖」：前端提交修改时带上它读到的版本，
     * 服务端发现版本已经变了就拒绝并要求刷新，而不是默默把别人刚做的设置盖掉。
     * 每次成功更新都会自增。</p>
     */
    private Long ragRevision;

    /**
     * 最近一次写入消息的时间。
     *
     * <p>会话列表按它倒序分页，所以每保存一条消息都要同步刷新它，
   * 否则刚聊过的会话不会浮到列表顶部。</p>
     */
    private LocalDateTime lastMessageTime;

    /**
     * 上下文版本号，每当这段会话的历史被改写（取消运行、消息失效、删除会话、压缩摘要）就自增。
     *
     * <p>它防的是「用过期上下文继续对话」：前端和运行记录都会带着自己看到的版本号，
 * 一旦服务端的版本已经往前走了，说明历史已经变了，那份缓存好的上下文和摘要就不能再用，
 * 必须重新装配。没有这个版本号，用户取消了一轮回答后，被取消的内容仍可能被当成有效历史喂给模型。</p>
     */
    private Long contextRevision;
}
