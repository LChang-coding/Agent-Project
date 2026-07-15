package cn.bugstack.ai.api.dto.usage;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型用量响应。
 */
@Data
@Builder
public class ModelUsageResponseDTO {
    private LatestCall latest;
    private Summary session;
    private Summary run;
    private Summary recent;

    @Data
    @Builder
    public static class LatestCall {
        private String callId;
        private String runId;
        private String invocationId;
        private String modelVersion;
        private String callStatus;
        private String finishReason;
        private Integer promptTokens;
        private Integer candidateTokens;
        private Integer totalTokens;
        private Integer thoughtsTokens;
        private Integer toolUsePromptTokens;
        private LocalDateTime createTime;
    }

    @Data
    @Builder
    public static class Summary {
        private Long callCount;
        private Long successCount;
        private Long failedCount;
        private Long runningCount;
        private Long cancelledCount;
        private Long promptTokens;
        private Long candidateTokens;
        private Long totalTokens;
        private Long thoughtsTokens;
        private Long toolUsePromptTokens;
    }
}
