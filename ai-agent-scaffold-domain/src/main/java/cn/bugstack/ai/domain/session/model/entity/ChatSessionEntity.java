package cn.bugstack.ai.domain.session.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionEntity {

    private String tenantId;

    private String userId;

    private String sessionId;

    private String agentId;

    private String agentName;

    /** 运行目标类型：agent/workflow。 */
    private String sourceType;

    /** 工作流实际运行版本；Agent 会话为空。 */
    private Integer workflowVersion;

    /** 工作流实际运行模型；Agent 会话为空。 */
    private String modelCode;

    private String appName;

    private String title;

    private String status;

    /** 是否在后续会话运行中启用RAG检索，旧会话默认关闭。 */
    private Boolean ragEnabled;

    private LocalDateTime lastMessageTime;

    /**
     * 有效上下文版本。
     */
    private Long contextRevision;
}
