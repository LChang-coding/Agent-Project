package cn.bugstack.ai.domain.share.model;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话分享操作结果。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SessionShareResultEntity {
    /** 分享授权元数据。 */
    private SessionShareEntity share;
    /** 仅创建响应返回一次的原始访问令牌。 */
    private String token;
    /** 导入后生成或加载的接收方会话。 */
    private ChatSessionEntity session;
    /** 导入会话的有效消息。 */
    private List<ChatMessageEntity> messages;
    /** 下载场景返回的已验真快照字节。 */
    private byte[] exportBytes;
    /** 源类型：agent 或 workflow。 */
    private String sourceType;
    /** 源工作流标识。 */
    private String workflowId;
    /** 源工作流版本。 */
    private Integer workflowVersion;
    /** 源会话模型代码。 */
    private String modelCode;
    /** 源 Agent 标识。 */
    private String sourceAgentId;
    /** 源 Agent 展示名。 */
    private String sourceAgentName;
    /** 源应用名。 */
    private String sourceAppName;
    /** 快照声明的工具依赖。 */
    private List<SessionToolDependencyEntity> toolDependencies;
    /** 接收方对工具依赖的权限差异。 */
    private SessionToolPrecheckEntity toolPrecheck;
    /** 是否为缺少显式工作流字段的旧版快照。 */
    private Boolean legacySnapshot;
}
