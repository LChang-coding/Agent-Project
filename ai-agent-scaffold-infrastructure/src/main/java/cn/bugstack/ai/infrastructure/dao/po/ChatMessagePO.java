package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 会话内按序持久化且可失效的消息。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatMessagePO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 消息归属用户ID
     */
    private String userId;

    /**
     * 会话业务ID
     */
    private String sessionId;

    /**
     * 消息业务ID
     */
    private String messageId;

    /**
     * 所属运行ID。
     */
    private String runId;

    /**
     * 消息有效性状态。
     */
    private String validityStatus;

    /**
     * 失效原因。
     */
    private String invalidReason;

    /**
     * 失效时间。
     */
    private java.time.LocalDateTime invalidatedAt;

    /**
     * 消息角色：user/assistant/tool/system
     */
    private String role;

    /**
     * 内容类型：text/json/markdown/file_ref
     */
    private String contentType;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 上下文 token 预估值
     */
    private Integer estimatedTokenCount;

    /**
     * 会话内消息序号
     */
    private Integer sequenceNo;

    /**
     * 父消息ID
     */
    private String parentMessageId;

    /**
     * 链路ID
     */
    private String traceId;

    /**
     * 扩展信息
     */
    private String metadata;
}
