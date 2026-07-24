package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatSessionPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 会话归属用户ID
     */
    private String userId;

    /**
     * 会话业务ID
     */
    private String sessionId;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent 名称
     */
    private String agentName;

    /**
     * 运行目标类型：agent/workflow
     */
    private String sourceType;

    /**
     * 工作流实际运行版本
     */
    private Integer workflowVersion;

    /**
     * 工作流实际运行模型
     */
    private String modelCode;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 会话状态：active/archived/deleted
     */
    private String status;

    /** 是否启用会话RAG。 */
    private Boolean ragEnabled;

    /** 会话RAG选择模式。 */
    private String ragMode;

    /** 会话RAG策略乐观锁版本。 */
    private Long ragRevision;

    /**
     * 最后消息时间
     */
    private LocalDateTime lastMessageTime;

    /**
     * 有效上下文版本。
     */
    private Long contextRevision;

    /**
     * 扩展信息
     */
    private String metadata;
}
