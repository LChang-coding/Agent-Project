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
    private String schemaVersion;
    private LocalDateTime exportedAt;
    private Session session;
    private List<Message> messages;
    private List<SessionToolDependencyEntity> toolDependencies;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Session {
        private String title;
        private String agentId;
        private String agentName;
        private String appName;
        private String sourceType;
        private String workflowId;
        private Integer workflowVersion;
        private String modelCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private Integer sequenceNo;
        private String role;
        private String contentType;
        private String content;
        private LocalDateTime createdAt;
    }
}
