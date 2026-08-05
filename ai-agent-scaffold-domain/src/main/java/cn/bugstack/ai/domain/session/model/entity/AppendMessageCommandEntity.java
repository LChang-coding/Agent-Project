package cn.bugstack.ai.domain.session.model.entity;

import lombok.Data;

/**
 * 「往某个会话里追加一条消息」这件事的全部入参。
 *
 * <p>所属层次：领域层（domain）session 子域的入参模型。</p>
 *
 * <p>谁会创建它：{@code SessionDomain} 内部的四个 appendUserMessage / appendAssistantMessage
 * 重载都把参数装进这个命令，再交给同一个私有的 appendMessage 落库。
 * 这样做的好处是：无论从哪个入口来的消息，校验、加锁、取序号、写库、刷新会话活跃时间
 * 都走同一条路径，不会出现某个入口漏掉加锁或漏刷时间的情况。</p>
 *
 * <p>它不负责什么：不生成 messageId、不算序号、不算 token（这三样都在落库时由领域服务统一产生），
 * 也不做任何校验。</p>
 */
@Data
public class AppendMessageCommandEntity {

    /**
     * 目标租户；参与会话加锁查询的隔离条件，个人模式为空。
  *
     * <p>注意最终落库用的租户取自查出来的会话行，而不是这里的值，
   * 所以这里只是「用来找到会话」的条件，不是可以随意写入的数据。</p>
   */
 private String tenantId;

 /**
     * 目标会话的拥有者；必须是可信身份。
     *
     * <p>落库前会拿它去锁会话，锁不到就抛「会话不存在」。这是消息写入的越权闸门——
 * 传了别人的会话号但用户对不上，就查不到会话，消息根本写不进去。</p>
 */
    private String userId;

    /**
     * 目标会话编号；决定这条消息接在哪段历史后面。
     */
    private String sessionId;

    /**
     * 产生这条消息的运行编号；历史兼容场景可以为空。
     *
   * <p>有它才能在取消运行时按 runId 把这一轮产生的消息批量置为失效。</p>
     */
    private String runId;

    /**
     * 说话人角色，只允许 user 或 assistant。
     *
     * <p>为空会被校验直接拒绝，因为角色缺失的消息喂给模型会让它分不清问答。</p>
  */
    private String role;

    /**
     * 内容协议，当前只用 text。
   */
 private String contentType;

  /**
     * 消息正文；为空会被校验拒绝，同时它也是估算 token 的输入。
     */
    private String content;

    /**
     * 可选的父消息编号，为未来的对话分支预留，当前流程不填。
     */
  private String parentMessageId;

    /**
     * 链路标识，落库后用于把这条消息与模型调用、工具调用和日志串起来排查问题。
     */
    private String traceId;

    /**
     * 扩展元数据 JSON；助手消息用它携带引用校验结果，与消息同一条记录落库以保证两者永远一致。
  */
    private String metadata;
}
