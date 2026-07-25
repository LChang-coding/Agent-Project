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
    /** 工具协议类型。 */
    private String toolType;
    /** 工具稳定标识。 */
    private String toolId;
    /** 工具展示名。 */
    private String toolName;
    /** 分享时实际使用的版本。 */
    private String version;
    /** 依赖证据来源。 */
    private String source;
    /** available 或 missing。 */
    private String access;
    /** 缺失或版本不匹配的可读原因。 */
    private String reason;
}
