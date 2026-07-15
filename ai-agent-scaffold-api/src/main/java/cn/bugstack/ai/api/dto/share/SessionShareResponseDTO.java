package cn.bugstack.ai.api.dto.share;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话分享、预览和导入响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionShareResponseDTO {
    private String shareId;
    private String shareUrl;
    private String downloadUrl;
    private String status;
    private LocalDateTime expiresAt;
    private Integer maxDownloads;
    private Integer downloadCount;
    private Integer messageCount;
    private String title;
    private String sessionId;
    private String agentId;
    private String agentName;
    private String appName;
    private Integer formatVersion;
    private String sourceType;
    private String workflowId;
    private Integer workflowVersion;
    private String modelCode;
    private Boolean legacySnapshot;
    private List<ToolDependency> toolDependencies;
    private ToolPrecheck toolPrecheck;
    private List<Message> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolDependency {
        private String toolType;
        private String toolId;
        private String toolName;
        private String version;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolAccess {
        private String toolType;
        private String toolId;
        private String toolName;
        private String version;
        private String source;
        private String access;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolPrecheck {
        private Boolean hasRisk;
        private Integer availableCount;
        private Integer missingCount;
        private Integer deniedCount;
        private List<ToolAccess> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String id;
        private String role;
        private String contentType;
        private String content;
        private Integer sequenceNo;
        private LocalDateTime createdAt;
    }
}
