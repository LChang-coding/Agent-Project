package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话所有权、运行目标和上下文版本的聚合根。 */
@Data
@Builder
public class ChatSessionEntity {

    /** 会话所属租户。 */
    private String tenantId;

    /** 会话所属用户。 */
    private String userId;

    /** 会话业务标识。 */
    private String sessionId;

    /** 普通 Agent 或工作流根 Agent 标识。 */
    private String agentId;

    /** 运行目标展示名。 */
    private String agentName;

    /** 运行目标类型：agent/workflow。 */
    private String sourceType;

    /** 工作流实际运行版本；Agent 会话为空。 */
    private Integer workflowVersion;

    /** 工作流实际运行模型；Agent 会话为空。 */
    private String modelCode;

    /** 应用展示名。 */
    private String appName;

    /** 会话标题。 */
    private String title;

    /** active 或 deleted。 */
    private String status;

    /** 是否在后续会话运行中启用RAG检索，旧会话默认关闭。 */
    private Boolean ragEnabled;

    /** 会话RAG选择模式：OFF/AUTO/MANUAL。 */
    private String ragMode;

    /** 会话RAG策略乐观锁版本。 */
    private Long ragRevision;

    /** 最近一次消息写入时间，用于会话游标排序。 */
    private LocalDateTime lastMessageTime;

    /**
     * 有效上下文版本。
     */
    private Long contextRevision;
}
