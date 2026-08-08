package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 启动智能工作流所需的可信身份与客户端输入。 */
@Data
@Builder
public class IntelligentWorkflowStartCommandEntity {

    /** 从认证上下文取得的租户标识。 */
    private String tenantId;

    /** 从认证上下文取得的用户标识。 */
    private String userId;

    /** 当前用户角色，用于加载工作流时校验访问权限。 */
    private String roleCode;

    /** 待运行的智能工作流标识。 */
    private String workflowId;

    /** 指定的发布版本；为空时由领域服务选择最新发布版本。 */
    private Integer workflowVersion;

    /** 本次运行请求的模型覆盖；为空时使用节点或工作流默认模型。 */
    private String modelCode;

    /** 承载用户消息和运行结果的会话。 */
    private String sessionId;

    /** 触发本次智能工作流的用户文本。 */
    private String message;

    /** 客户端预生成的运行标识；为空时由服务端生成。 */
    private String requestedRunId;

    /** 本轮允许加入模型上下文的附件标识。 */
    private List<String> attachmentIds;
}
