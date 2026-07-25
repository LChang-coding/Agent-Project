package cn.bugstack.ai.domain.share.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 版本化会话导出文档，只承载允许跨用户复制的白名单字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionExportDocument {
    /** 导出协议版本，用于兼容性判定。 */
    private String schemaVersion;
    /** 快照生成时间。 */
    private LocalDateTime exportedAt;
    /** 会话级白名单元数据。 */
    private Session session;
    /** 按原顺序导出的有效文本消息。 */
    private List<Message> messages;
    /** 服务端从实际调用证据汇总出的工具依赖。 */
    private List<SessionToolDependencyEntity> toolDependencies;

    /** 不含源用户、租户和内部运行标识的会话快照。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Session {
        /** 分享展示标题。 */
        private String title;
        /** 源 Agent；工作流快照中兼作旧版工作流标识。 */
        private String agentId;
        /** 源 Agent 展示名。 */
        private String agentName;
        /** 源应用名。 */
        private String appName;
        /** agent 或 workflow。 */
        private String sourceType;
        /** 工作流来源时的显式工作流标识。 */
        private String workflowId;
        /** 工作流发布版本。 */
        private Integer workflowVersion;
        /** 原会话选择的模型代码。 */
        private String modelCode;
    }

    /** 仅包含可复制文本的单条消息快照。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /** 源会话中的稳定顺序号。 */
        private Integer sequenceNo;
        /** 仅允许 user 或 assistant。 */
        private String role;
        /** 当前协议仅允许 text。 */
        private String contentType;
        /** 消息正文。 */
        private String content;
        /** 源消息创建时间。 */
        private LocalDateTime createdAt;
    }
}
