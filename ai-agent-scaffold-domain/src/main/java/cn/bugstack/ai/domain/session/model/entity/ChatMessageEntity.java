package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

/** 数据库中的一条会话消息事实。 */
@Data
@Builder
public class ChatMessageEntity {

    /** 消息所属租户。 */
    private String tenantId;

    /** 消息所属用户。 */
    private String userId;

    /** 消息所属会话。 */
    private String sessionId;

    /** 消息业务标识。 */
    private String messageId;

    /** 产生消息的运行标识。 */
    private String runId;

    /** active 或 invalid。 */
    private String validityStatus;

    /** 取消、引导或删除导致失效的原因。 */
    private String invalidReason;

    /** 消息失效时间。 */
    private java.time.LocalDateTime invalidatedAt;

    /** user 或 assistant。 */
    private String role;

    /** 消息内容协议，当前为 text。 */
    private String contentType;

    /** 消息正文。 */
    private String content;

    /**
     * 上下文 token 预估值。
     */
    private Integer estimatedTokenCount;

    /** 会话内严格递增的显示与上下文顺序。 */
    private Integer sequenceNo;

    /** 可选父消息，用于未来分支关系。 */
    private String parentMessageId;

    /** 产生消息的链路标识。 */
    private String traceId;

    /** 版本化的安全扩展元数据 JSON。 */
    private String metadata;

    /** 数据库创建时间。 */
    private java.time.LocalDateTime createTime;
}
