package cn.bugstack.ai.domain.session.model.entity;

import lombok.Data;

/** 向可信会话追加消息的领域命令。 */
@Data
public class AppendMessageCommandEntity {

    /** 目标租户。 */
    private String tenantId;

    /** 目标会话所有者。 */
    private String userId;

    /** 目标会话。 */
    private String sessionId;

    /** 产生该消息的运行；兼容历史消息时可空。 */
    private String runId;

    /** user 或 assistant。 */
    private String role;

    /** 当前只允许 text。 */
    private String contentType;

    /** 消息正文。 */
    private String content;

    /** 可选的父消息标识。 */
    private String parentMessageId;

    /** 串联模型、工具和日志的链路标识。 */
    private String traceId;

    /** 版本化安全扩展元数据 JSON。 */
    private String metadata;
}
