package cn.bugstack.ai.domain.share.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 会话分享中服务端计算的工具依赖。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionToolDependencyEntity {
    private String toolType;
    private String toolId;
    private String toolName;
    private String version;
    private String source;
}
