package cn.bugstack.ai.domain.session.model.entity;

import lombok.Data;

/**
 * 「新建一个会话」这件事允许调用方提供的字段白名单。
 *
 * <p>所属层次：领域层（domain）session 子域的入参模型。</p>
 *
 * <p>谁会创建它：对话领域服务在建会话时装填。谁会消费它：{@code SessionDomain#createSession}。</p>
 *
 * <p>为什么强调「白名单」：会话实体上还有状态、检索开关、检索版本、上下文版本、最后活跃时间等字段，
 * 这些一律由服务端在创建时按固定初值写入，不接受调用方指定。否则前端就能建出一个
 * 「一开始就开着检索」或「上下文版本非零」的会话，绕过默认策略并让后续版本校验失效。</p>
 *
 * <p>它不负责什么：不校验参数（校验在领域服务的 checkCreateCommand 里）、不生成会话编号。</p>
 */
@Data
public class CreateSessionCommandEntity {

    /**
     * 新会话所属租户，取自认证上下文；决定这段对话被隔离在哪个租户范围内，个人模式为空。
     */
    private String tenantId;

    /**
     * 新会话所属用户，必须是可信身份；为空会被校验拒绝，因为无主的会话谁都能读写。
     */
    private String userId;

    /**
     * 会话编号，由上层生成后传入；为空会被校验拒绝。
     *
     * <p>由上层而不是数据库生成，是为了在写库之前就能把这个编号返回给前端并用于日志串联。</p>
   */
    private String sessionId;

    /**
     * 这段会话要跑的 Agent 编号；为空会被校验拒绝，因为没有执行目标的会话发不出消息。
     */
    private String agentId;

    /**
     * Agent 展示名；只用于界面显示，同时作为会话标题的兜底值。
     */
    private String agentName;

    /**
     * 会话类型：agent 或 workflow；不填时服务端兜底成 agent。
     *
     * <p>它决定后续发消息走哪条执行路径，创建后不再变更。</p>
     */
    private String sourceType;

    /**
     * 工作流会话要固化的发布版本号。
     *
  * <p>固化在会话上，这样工作流之后被改动或发新版都不影响这段已有对话的行为，
     * 避免同一段会话前后表现不一致。</p>
     */
    private Integer workflowVersion;

    /**
     * 工作流会话要固化的模型编号；同样是为了让这段会话的行为在整个生命周期内稳定。
     */
    private String modelCode;

  /**
     * 应用展示名，用于界面分组和日志标注，不参与任何业务判断。
     */
    private String appName;

    /**
 * 会话标题；不填时服务端用 Agent 名兜底，保证会话列表里不会出现空标题。
     */
    private String title;
}
