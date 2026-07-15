package cn.bugstack.ai.domain.share.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 接收方对单个分享工具依赖的访问预检结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionToolAccessEntity {
    private String toolType;
    private String toolId;
    private String toolName;
    private String version;
    private String source;
    private String access;
    private String reason;
}
