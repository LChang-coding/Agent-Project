package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 一条已经落库的对话消息，是「谁在第几轮说了什么」这一事实的完整记录。
 *
 * <p>所属层次：领域层（domain）session 子域的实体，对应数据库表 {@code chat_message}。</p>
 *
 * <p>谁会用它：{@code SessionDomain} 负责创建和查询；上下文装配读它拼历史；
 * 会话分享导出读它生成文档；控制器层转成 DTO 返回前端渲染气泡。</p>
 *
 * <p>角色与顺序是这个实体最重要的两件事：role 区分这句话是用户说的还是助手答的，
 * 模型只有靠它才能理解对话的来回；sequenceNo 决定顺序，写入时在会话行加锁后取最大值加一，
 * 保证并发发消息也不会拿到相同序号，历史顺序永远确定。</p>
 *
 * <p>它不负责什么：不做权限判断、不自己落库、不携带附件内容（附件通过 messageId 反向关联）。</p>
 */
@Data
@Builder
public class ChatMessageEntity {

    /**
     * 消息所属租户，与会话保持一致；查询时作为隔离条件，个人模式为 null。
  */
    private String tenantId;

    /**
     * 消息所属用户，与会话保持一致。
     *
     * <p>它不是从请求里抄来的，而是保存消息时从数据库里查出的会话行上取的，
     * 这样即便调用方传了别的用户编号，也不可能把消息写到别人名下。</p>
     */
    private String userId;

    /**
     * 消息所属会话；决定它出现在哪段对话历史里。
     */
    private String sessionId;

    /**
     * 消息业务标识（msg_ 前缀 + UUID），服务端生成。
     *
     * <p>附件绑定、引用溯源、单条消息查询都靠它。用随机串而不是自增数字，
 * 避免别人按数字顺序猜测并试探其他消息。</p>
  */
    private String messageId;

    /**
   * 产生这条消息的那次运行编号；历史遗留消息可能为空。
     *
     * <p>取消一次运行时，会按 runId 把这次运行产生的消息批量置为失效，
   * 所以没有 runId 的消息无法被这种方式撤销。</p>
     */
    private String runId;

  /**
     * 消息有效性：active 参与上下文和展示，invalid 已失效。
     *
     * <p>这是「撤回」的实现方式——不删记录，只标失效。上下文装配、历史查询、分享导出
     * 都只读 active 的消息，因此被取消或被重新生成覆盖的内容不会再影响模型。</p>
     */
    private String validityStatus;

    /**
     * 失效原因（用户取消、被新回答取代、会话删除等）。
     *
     * <p>纯粹为了排查和审计：出现「用户说他发过但界面没有」时，靠它判断是被谁、因为什么撤下的。</p>
     */
    private String invalidReason;

    /**
     * 失效发生的时间点，与失效原因配合还原当时的处理顺序。
     */
 private java.time.LocalDateTime invalidatedAt;

    /**
     * 说话人角色：user 表示用户输入，assistant 表示模型回答。
     *
     * <p>它是喂给模型时区分「问」和「答」的依据，角色错乱会让模型把自己的回答当成用户要求。
     * 一轮正常对话的落库顺序固定是先 user 后 assistant——用户消息在启动运行时就写入，
     * 助手消息要等生成完才写，因此中途失败时可能只有 user 消息而没有 assistant 消息。</p>
     */
    private String role;

    /**
     * 内容协议，当前固定 text；预留给以后的图片、结构化内容等形态。
     */
    private String contentType;

    /**
     * 消息正文，用户输入的原话或模型输出的完整回答。
     */
    private String content;

    /**
     * 这条消息估算占多少 token，保存时用字符规则算好一次。
 *
     * <p>上下文裁剪要反复统计历史占了多少预算，如果每次都重新算就太慢，
     * 所以在写入时算一次存下来。它是保守估算值，不等于模型真实计费的 token 数。</p>
     */
    private Integer estimatedTokenCount;

    /**
     * 会话内严格递增的顺序号，决定展示顺序和上下文顺序。
     *
     * <p>生成方式是「锁住会话行后取当前最大值加一」，所以并发写入不会撞号。
     * 上下文装配用它划定可见范围（到第几号为止）和摘要覆盖范围（第几号之前已被压缩）。</p>
     */
  private Integer sequenceNo;

  /**
     * 可选的父消息编号，为将来的对话分支（同一个问题生成多个答案）预留，当前流程不写。
  */
    private String parentMessageId;

    /**
     * 产生这条消息的链路标识。
     *
     * <p>它把前端、模型调用、工具调用和日志串在一起。用户反馈「这条回答有问题」时，
     * 靠它就能一次捞出整条链路的日志，不必按时间瞎猜。</p>
     */
 private String traceId;

    /**
     * 扩展元数据 JSON，带版本号。
     *
     * <p>助手消息的引用（citation）校验结果就存在这里：这次回答引用了哪些文档、
     * 哪些引用是模型编造的。之所以随消息一起落库，是为了保证界面显示的引用和数据库完全一致，
     * 不会出现「界面说引用了文档但库里查不到」的情况。</p>
*/
  private String metadata;

    /**
     * 数据库写入时间，由数据库生成，用于审计和按时间排查。
     */
    private java.time.LocalDateTime createTime;
}
