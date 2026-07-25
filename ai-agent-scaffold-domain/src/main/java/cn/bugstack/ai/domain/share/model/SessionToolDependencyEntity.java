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
    /** 工具协议类型。 */
    private String toolType;
    /** 工具稳定标识。 */
    private String toolId;
    /** 分享展示用工具名。 */
    private String toolName;
    /** 会话实际调用的工具版本。 */
    private String version;
    /** 工作流配置或运行证据等依赖来源。 */
    private String source;
}
