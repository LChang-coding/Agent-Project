package cn.bugstack.ai.domain.session.model.entity;

import lombok.Data;

/** 创建会话时允许调用方提供的白名单字段。 */
@Data
public class CreateSessionCommandEntity {

    /** 新会话所属租户。 */
    private String tenantId;

    /** 新会话所属用户。 */
    private String userId;

    /** 服务端或上层生成的会话标识。 */
    private String sessionId;

    /** 运行目标 Agent。 */
    private String agentId;

    /** 运行目标展示名。 */
    private String agentName;

    /** agent 或 workflow。 */
    private String sourceType;

    /** 工作流来源时固化的发布版本。 */
    private Integer workflowVersion;

    /** 工作流来源时固化的模型。 */
    private String modelCode;

    /** 应用展示名。 */
    private String appName;

    /** 可选会话标题。 */
    private String title;
}
