package cn.bugstack.ai.api.dto.share;

import lombok.Data;

/**
 * 创建会话分享请求。
 */
@Data
public class CreateSessionShareRequestDTO {
    private String sessionId;
    private Integer validHours;
    private Integer maxDownloads;
}
